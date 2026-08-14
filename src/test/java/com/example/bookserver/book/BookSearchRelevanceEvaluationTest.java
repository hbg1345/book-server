package com.example.bookserver.book;

import com.example.bookserver.TestcontainersConfiguration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline relevance evaluation over a frozen, stratified sample of real production titles.
 *
 * <p>The custom Gradle task intentionally loads the full ~103k seed catalogue. Queries with one
 * objectively known target are derived deterministically from the production titles; ambiguous
 * keyword relevance is excluded and belongs in a separately human-judged qrels set.
 */
@MybatisTest
@Import(TestcontainersConfiguration.class)
@Tag("search-quality")
class BookSearchRelevanceEvaluationTest {

    private static final Pattern ELIGIBLE_TYPO_WORD = Pattern.compile("[A-Za-z]{5,}");
    private static final int RESULT_LIMIT = 10;
    private static final int BOOTSTRAP_ROUNDS = 5_000;

    @Autowired
    private BookMapper bookMapper;

    @Test
    void reportNavigationalRelevance() throws Exception {
        List<SampleTitle> titles = loadFrozenProductionSample();
        List<EvaluationCase> cases = buildCases(titles);
        List<EvaluationResult> results = cases.stream().map(this::evaluate).toList();

        System.out.println(renderReport(results));

        assertThat(titles).hasSize(60);
        assertThat(results).hasSizeGreaterThan(120);
    }

    private EvaluationResult evaluate(EvaluationCase evaluationCase) {
        List<Book> returned = bookMapper.searchByTitle(evaluationCase.query(), 0, RESULT_LIMIT);
        int rank = 0;
        for (int i = 0; i < returned.size(); i++) {
            if (returned.get(i).getBookTitle().equalsIgnoreCase(evaluationCase.expectedTitle())) {
                rank = i + 1;
                break;
            }
        }
        return new EvaluationResult(evaluationCase, rank);
    }

    private List<EvaluationCase> buildCases(List<SampleTitle> titles) {
        List<EvaluationCase> cases = new ArrayList<>();
        for (int index = 0; index < titles.size(); index++) {
            SampleTitle title = titles.get(index);
            Split split = index % 5 == 0 ? Split.HOLDOUT : Split.DEVELOPMENT;
            cases.add(new EvaluationCase(
                    title.uuid(), title.title(), title.title(), QueryKind.EXACT, split));

            String typo = deleteOneCharacter(title.title());
            if (typo != null) {
                cases.add(new EvaluationCase(
                        title.uuid(), typo, title.title(), QueryKind.ONE_CHAR_TYPO, split));
            }

            String punctuationNormalized = title.title()
                    .replaceAll("[\\p{Punct}]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!punctuationNormalized.equalsIgnoreCase(title.title())) {
                cases.add(new EvaluationCase(title.uuid(), punctuationNormalized, title.title(),
                        QueryKind.PUNCTUATION_NORMALIZED, split));
            }
        }
        return cases;
    }

    private String deleteOneCharacter(String title) {
        Matcher matcher = ELIGIBLE_TYPO_WORD.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        int deletionIndex = matcher.start() + (matcher.end() - matcher.start()) / 2;
        return title.substring(0, deletionIndex) + title.substring(deletionIndex + 1);
    }

    private String renderReport(List<EvaluationResult> results) {
        StringBuilder report = new StringBuilder("\n=== BOOK SEARCH RELEVANCE: CURRENT IMPLEMENTATION ===\n")
                .append("Catalogue: full production seed (~103k books)\n")
                .append("Sample: 60 titles read from production Cloud SQL, stratified by length\n")
                .append("Relevance: one known navigational target per query\n");

        appendMetrics(report, "ALL", results);
        for (Split split : Split.values()) {
            appendMetrics(report, split.name(), results.stream()
                    .filter(result -> result.evaluationCase().split() == split)
                    .toList());
        }
        for (QueryKind kind : QueryKind.values()) {
            appendMetrics(report, kind.name(), results.stream()
                    .filter(result -> result.evaluationCase().kind() == kind)
                    .toList());
        }
        return report.toString();
    }

    private void appendMetrics(
            StringBuilder report, String label, List<EvaluationResult> results) {
        MetricPoint point = metrics(results);
        ConfidenceInterval hitInterval = bootstrap(results, Metric.HIT_AT_10);
        ConfidenceInterval mrrInterval = bootstrap(results, Metric.MRR_AT_10);
        report.append(String.format(Locale.ROOT,
                "%s: n=%d, Top-1=%.1f%%, Hit@10=%.1f%% [95%% CI %.1f..%.1f], "
                        + "MRR@10=%.3f [95%% CI %.3f..%.3f]%n",
                label, results.size(), point.top1() * 100, point.hitAt10() * 100,
                hitInterval.low() * 100, hitInterval.high() * 100,
                point.mrrAt10(), mrrInterval.low(), mrrInterval.high()));
    }

    private MetricPoint metrics(List<EvaluationResult> results) {
        double top1 = results.stream().filter(result -> result.rank() == 1).count()
                / (double) results.size();
        double hitAt10 = results.stream().filter(result -> result.rank() > 0).count()
                / (double) results.size();
        double mrrAt10 = results.stream()
                .mapToDouble(result -> result.rank() == 0 ? 0.0 : 1.0 / result.rank())
                .average()
                .orElse(0.0);
        return new MetricPoint(top1, hitAt10, mrrAt10);
    }

    private ConfidenceInterval bootstrap(List<EvaluationResult> results, Metric metric) {
        Map<UUID, List<EvaluationResult>> bySourceTitle = new LinkedHashMap<>();
        for (EvaluationResult result : results) {
            bySourceTitle.computeIfAbsent(result.evaluationCase().sourceUuid(), ignored ->
                    new ArrayList<>()).add(result);
        }
        List<List<EvaluationResult>> clusters = new ArrayList<>(bySourceTitle.values());
        Random random = new Random(31L * clusters.size() + metric.ordinal());
        List<Double> estimates = new ArrayList<>(BOOTSTRAP_ROUNDS);
        for (int round = 0; round < BOOTSTRAP_ROUNDS; round++) {
            double total = 0;
            int observations = 0;
            for (int draw = 0; draw < clusters.size(); draw++) {
                List<EvaluationResult> cluster = clusters.get(random.nextInt(clusters.size()));
                for (EvaluationResult result : cluster) {
                    total += metric.value(result);
                    observations++;
                }
            }
            estimates.add(total / observations);
        }
        estimates.sort(Comparator.naturalOrder());
        return new ConfidenceInterval(
                estimates.get((int) (BOOTSTRAP_ROUNDS * 0.025)),
                estimates.get((int) (BOOTSTRAP_ROUNDS * 0.975)));
    }

    private List<SampleTitle> loadFrozenProductionSample() throws Exception {
        InputStream resource = getClass().getResourceAsStream(
                "/search-quality/production-book-title-sample.tsv");
        assertThat(resource).as("frozen production title sample").isNotNull();
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return input.lines().skip(1).map(line -> {
                String[] fields = line.split("\\t", 2);
                return new SampleTitle(UUID.fromString(fields[0]), fields[1]);
            }).toList();
        }
    }

    private enum QueryKind {
        EXACT,
        ONE_CHAR_TYPO,
        PUNCTUATION_NORMALIZED
    }

    private enum Split {
        DEVELOPMENT,
        HOLDOUT
    }

    private enum Metric {
        HIT_AT_10 {
            @Override
            double value(EvaluationResult result) {
                return result.rank() > 0 ? 1.0 : 0.0;
            }
        },
        MRR_AT_10 {
            @Override
            double value(EvaluationResult result) {
                return result.rank() == 0 ? 0.0 : 1.0 / result.rank();
            }
        };

        abstract double value(EvaluationResult result);
    }

    private record SampleTitle(UUID uuid, String title) {
    }

    private record EvaluationCase(
            UUID sourceUuid, String query, String expectedTitle, QueryKind kind, Split split) {
    }

    private record EvaluationResult(EvaluationCase evaluationCase, int rank) {
    }

    private record MetricPoint(double top1, double hitAt10, double mrrAt10) {
    }

    private record ConfidenceInterval(double low, double high) {
    }
}
