package com.example.bookserver.address;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.bookserver.address.dto.CreateAddressRequest;
import com.example.bookserver.auth.JwtProvider;
import com.example.bookserver.auth.SecurityConfig;
import com.example.bookserver.common.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.example.bookserver.docs.ApiDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice test for the address-book endpoints under the real security filter
 * chain. Every route requires an authenticated principal (the user's uuid, as the JWT
 * filter sets it) and delegates to the service scoped to that uuid.
 */
@WebMvcTest(AddressController.class)
@AutoConfigureRestDocs
@org.springframework.context.annotation.Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtProvider.class})
class AddressControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressService addressService;

    /** Authenticate as the given user uuid (what the JWT filter does). */
    private static RequestPostProcessor asUser(UUID uuid) {
        return authentication(new UsernamePasswordAuthenticationToken(uuid, null, List.of()));
    }

    private static final String VALID_BODY = """
            {
              "alias":"Home","recipient":"Jane Doe","phone":"010-1234-5678",
              "country":"KR","roadAddress":"서울특별시 강남구 테헤란로 1",
              "detailAddress":"101동 1001호","postalCode":"06234","defaultAddress":true
            }
            """;

    // create: 201 with the new address id; the service is called with the authenticated
    // user and the parsed body (field binding verified via the captured request).
    @Test
    void addAddress_returns201_andDelegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        when(addressService.addAddress(eq(user), any(CreateAddressRequest.class))).thenReturn(newId);

        mockMvc.perform(post("/api/addresses").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressUuid").value(newId.toString()))
                .andDo(document("address-create",
                        requestFields(
                                fieldWithPath("alias").description("배송지 별칭(예: 집, 회사)"),
                                fieldWithPath("recipient").description("수령인 이름"),
                                fieldWithPath("phone").description("수령인 전화번호"),
                                fieldWithPath("country").description("두 글자 국가 코드(예: KR)"),
                                fieldWithPath("roadAddress").description("도로명 주소"),
                                fieldWithPath("detailAddress").optional().description("상세 주소(선택)"),
                                fieldWithPath("postalCode").description("우편번호(국가별 형식 검사)"),
                                fieldWithPath("defaultAddress").description("기본 배송지 여부")),
                        responseFields(
                                fieldWithPath("addressUuid").description("등록된 배송지 식별자(UUID)"))));

        ArgumentCaptor<CreateAddressRequest> captor = ArgumentCaptor.forClass(CreateAddressRequest.class);
        verify(addressService).addAddress(eq(user), captor.capture());
        CreateAddressRequest sent = captor.getValue();
        assertThat(sent.alias()).isEqualTo("Home");
        assertThat(sent.country()).isEqualTo("KR");
        assertThat(sent.postalCode()).isEqualTo("06234");
        assertThat(sent.defaultAddress()).isTrue();
    }

    // create without authentication -> 401; service never touched.
    @Test
    void addAddress_returns401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(post("/api/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andDo(document("address-create--addAddress_returns401_whenNotAuthenticated"));

        verify(addressService, never()).addAddress(any(), any());
    }

    // create with an invalid body (blank recipient) -> 400; service never reached.
    @Test
    void addAddress_returns400_whenBodyInvalid() throws Exception {
        UUID user = UUID.randomUUID();
        String invalid = VALID_BODY.replace("\"Jane Doe\"", "\"\"");   // blank recipient

        mockMvc.perform(post("/api/addresses").with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andDo(document("address-create--addAddress_returns400_whenBodyInvalid"));

        verify(addressService, never()).addAddress(any(), any());
    }

    // list: 200 with the caller's saved addresses (default first, as the service returns).
    @Test
    void myAddresses_returnsAddresses() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        Address a = new Address(addressId, user, "Home", "Jane Doe", "010-1234-5678",
                "KR", "서울특별시 강남구 테헤란로 1", "101동 1001호", "06234", true,
                LocalDateTime.of(2026, 8, 3, 10, 0));
        when(addressService.listMyAddresses(user)).thenReturn(List.of(a));

        mockMvc.perform(get("/api/addresses").with(asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].addressUuid").value(addressId.toString()))
                .andExpect(jsonPath("$[0].alias").value("Home"))
                .andExpect(jsonPath("$[0].country").value("KR"))
                .andExpect(jsonPath("$[0].postalCode").value("06234"))
                .andExpect(jsonPath("$[0].defaultAddress").value(true))
                .andDo(document("address-list",
                        responseFields(
                                fieldWithPath("[].addressUuid").description("배송지 식별자(UUID)"),
                                fieldWithPath("[].alias").description("배송지 별칭"),
                                fieldWithPath("[].recipient").description("수령인 이름"),
                                fieldWithPath("[].phone").description("수령인 전화번호"),
                                fieldWithPath("[].country").description("두 글자 국가 코드(예: KR)"),
                                fieldWithPath("[].roadAddress").description("도로명 주소"),
                                fieldWithPath("[].detailAddress").description("상세 주소(선택)"),
                                fieldWithPath("[].postalCode").description("우편번호"),
                                fieldWithPath("[].defaultAddress").description("기본 배송지 여부"),
                                fieldWithPath("[].createdAt").description("배송지 등록 일시"))));
    }

    // update: 200; delegates with the authenticated user, path id and parsed body.
    @Test
    void updateAddress_delegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();

        mockMvc.perform(put("/api/addresses/{addressUuid}", addressId).with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andDo(document("address-update", requestFields(
                        fieldWithPath("alias").description("배송지 별칭"),
                        fieldWithPath("recipient").description("수령인 이름"),
                        fieldWithPath("phone").description("수령인 전화번호"),
                        fieldWithPath("country").description("두 글자 국가 코드(예: KR)"),
                        fieldWithPath("roadAddress").description("도로명 주소"),
                        fieldWithPath("detailAddress").optional().description("상세 주소(선택)"),
                        fieldWithPath("postalCode").description("우편번호"),
                        fieldWithPath("defaultAddress").description("기본 배송지 여부"))));

        verify(addressService).updateAddress(eq(user), eq(addressId), any());
    }

    // update an address the caller does not own / missing -> 404.
    @Test
    void updateAddress_returns404_whenNotFound() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        doThrow(new AddressNotFoundException(addressId))
                .when(addressService).updateAddress(eq(user), eq(addressId), any());

        mockMvc.perform(put("/api/addresses/{addressUuid}", addressId).with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andDo(document("address-update--updateAddress_returns404_whenNotFound"));
    }

    // delete: 200; delegates with the authenticated user and path id.
    @Test
    void deleteAddress_delegatesToService() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();

        mockMvc.perform(delete("/api/addresses/{addressUuid}", addressId).with(asUser(user)))
                .andExpect(status().isOk())
                .andDo(document("address-delete"));

        verify(addressService).deleteAddress(user, addressId);
    }

    // delete an address the caller does not own / missing -> 404.
    @Test
    void deleteAddress_returns404_whenNotFound() throws Exception {
        UUID user = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        doThrow(new AddressNotFoundException(addressId))
                .when(addressService).deleteAddress(user, addressId);

        mockMvc.perform(delete("/api/addresses/{addressUuid}", addressId).with(asUser(user)))
                .andExpect(status().isNotFound())
                .andDo(document("address-delete--deleteAddress_returns404_whenNotFound"));
    }
}
