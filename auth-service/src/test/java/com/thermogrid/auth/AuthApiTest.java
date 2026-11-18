package com.thermogrid.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.auth.domain.AppUser;
import com.thermogrid.auth.repo.AppUserRepository;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;

    private MvcResult login(String user, String pw) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("username", user, "password", pw)))).andReturn();
    }

    @Test
    void validLoginReturnsTheDocumentedShapeAndAUsableToken() throws Exception {
        MvcResult res = login("operator", "test-operator-pw");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(res.getResponse().getContentAsString());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(28800);
        assertThat(body.get("user").get("username").asText()).isEqualTo("operator");
        assertThat(body.get("user").get("role").asText()).isEqualTo("OPERATOR");
        assertThat(res.getResponse().getContentAsString()).doesNotContain("test-operator-pw").doesNotContain("$2");

        String token = body.get("token").asText();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("\"sub\":\"operator\"").contains("\"role\":\"OPERATOR\"");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void allThreeRolesCanLogIn() throws Exception {
        assertThat(json.readTree(login("viewer", "test-viewer-pw").getResponse().getContentAsString())
                .get("user").get("role").asText()).isEqualTo("VIEWER");
        assertThat(json.readTree(login("admin", "test-admin-pw").getResponse().getContentAsString())
                .get("user").get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void wrongPasswordAndUnknownUserGetIdenticalResponses() throws Exception {
        MvcResult wrongPw = login("viewer", "not-the-password");
        MvcResult noUser = login("nobody", "not-the-password");
        assertThat(wrongPw.getResponse().getStatus()).isEqualTo(401);
        assertThat(noUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(noUser.getResponse().getContentAsString()).isEqualTo(wrongPw.getResponse().getContentAsString());
        assertThat(wrongPw.getResponse().getContentAsString()).doesNotContain("token");
    }

    @Test
    void blankCredentialsAreABadRequest() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meRejectsMissingMalformedAndTamperedTokens() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        String token = json.readTree(login("viewer", "test-viewer-pw").getResponse().getContentAsString())
                .get("token").asText();
        String[] p = token.split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"viewer\",\"role\":\"ADMIN\",\"iss\":\"thermogrid-auth\"}".getBytes());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + p[0] + "." + forged + "." + p[2]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() {
        AppUser u = users.findByUsername("admin").orElseThrow();
        assertThat(u.getPasswordHash()).startsWith("$2").doesNotContain("test-admin-pw");
    }
}
