package com.iwrite.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.support.TestDatabaseInitializer;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract for issue #144, Slice 144A. These requests exercise the real session, CSRF filter,
 * PostgreSQL constraints and tenant/book authorization. No test supplies an identity that the
 * profile endpoint is allowed to trust.
 */
@AutoConfigureMockMvc
@SpringBootTest
@Transactional
class ProfileIntegrationTest {

    private static final String PASSWORD = "senha-de-perfil-A1";

    @DynamicPropertySource
    static void keepProfileLoginsOutsideTheRateLimitContract(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "false");
        // This suite exercises profile behavior and performs a real login for every scenario. Give
        // it a separate high-budget context so those setup logins cannot consume the fixed-window
        // counters asserted by the dedicated authentication rate-limit suites.
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-origin", () -> "1000");
        registry.add("iwrite.auth.login-rate-limit.max-attempts-per-account", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Account accountA;
    private Account accountB;

    @BeforeEach
    void createAccounts() {
        accountA = createAccount("Ana", "ana", "America/Sao_Paulo", UserPersonaType.WRITER);
        accountB = createAccount("Bruno", "bruno", "UTC", UserPersonaType.REVIEWER);
    }

    @Test
    void getReturnsOnlyTheAuthenticatedUsersPrivateProfile() throws Exception {
        MockHttpSession session = login(accountA.email());

        mockMvc.perform(get("/api/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ana"))
                .andExpect(jsonPath("$.email").value(accountA.email()))
                .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(accountB.email()))));
    }

    @Test
    void getReturnsEveryPersonaAndExactlyOnePrimary() throws Exception {
        addPersona(accountA.userId(), UserPersonaType.EDITOR, false);
        addPersona(accountA.userId(), UserPersonaType.BETA_READER, false);

        mockMvc.perform(get("/api/profile").session(login(accountA.email())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas", hasSize(3)))
                .andExpect(jsonPath("$.personas[?(@.primary == true)]", hasSize(1)))
                .andExpect(jsonPath("$.personas[?(@.type == 'WRITER' && @.primary == true)]", hasSize(1)))
                .andExpect(jsonPath("$.personas[?(@.type == 'EDITOR')]", hasSize(1)))
                .andExpect(jsonPath("$.personas[?(@.type == 'BETA_READER')]", hasSize(1)));
    }

    @Test
    void getRejectsASessionWhoseMembershipWasRevoked() throws Exception {
        MockHttpSession session = login(accountA.email());
        revokeMembership(accountA.userId());

        mockMvc.perform(get("/api/profile").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchRejectsARevokedMembershipWithoutChangingAnyProfileField() throws Exception {
        MockHttpSession session = login(accountA.email());
        revokeMembership(accountA.userId());

        patchProfile(session, "Nome indevido", "America/Fortaleza",
                List.of("WRITER", "EDITOR"), "EDITOR")
                .andExpect(status().isUnauthorized());

        User unchanged = findUser(accountA.userId());
        assertThat(unchanged.getDisplayName()).isEqualTo("Ana");
        assertThat(unchanged.getTimeZoneId()).isEqualTo("America/Sao_Paulo");
        assertThat(personas(accountA.userId())).singleElement().satisfies(persona -> {
            assertThat(persona.getPersona()).isEqualTo(UserPersonaType.WRITER);
            assertThat(persona.isPrimary()).isTrue();
        });
    }

    @Test
    void patchChangesDisplayName() throws Exception {
        patchProfile(login(accountA.email()), "  Ana Maria  ", "America/Sao_Paulo",
                List.of("WRITER"), "WRITER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ana Maria"));

        assertThat(findUser(accountA.userId()).getDisplayName()).isEqualTo("Ana Maria");
    }

    @Test
    void patchChangesTimeZone() throws Exception {
        patchProfile(login(accountA.email()), "Ana", "America/Fortaleza",
                List.of("WRITER"), "WRITER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("America/Fortaleza"));

        assertThat(findUser(accountA.userId()).getTimeZoneId()).isEqualTo("America/Fortaleza");
    }

    @Test
    void patchAddsSecondAndThirdPersonasWithoutRecreatingTheExistingOne() throws Exception {
        UserPersona writerBefore = personas(accountA.userId()).getFirst();
        UUID writerId = writerBefore.getId();
        OffsetDateTime writerCreatedAt = writerBefore.getCreatedAt();

        patchProfile(login(accountA.email()), "Ana", "America/Sao_Paulo",
                List.of("WRITER", "EDITOR", "BETA_READER"), "WRITER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas", hasSize(3)));

        List<UserPersona> saved = personas(accountA.userId());
        assertThat(saved).extracting(UserPersona::getPersona)
                .containsExactlyInAnyOrder(UserPersonaType.WRITER, UserPersonaType.EDITOR, UserPersonaType.BETA_READER);
        UserPersona writerAfter = saved.stream()
                .filter(persona -> persona.getPersona() == UserPersonaType.WRITER)
                .findFirst().orElseThrow();
        assertThat(writerAfter.getId()).isEqualTo(writerId);
        assertThat(writerAfter.getCreatedAt()).isEqualTo(writerCreatedAt);
    }

    @Test
    void patchRemovesOnlyANonPrimaryPersona() throws Exception {
        UserPersona editor = addPersona(accountA.userId(), UserPersonaType.EDITOR, false);
        UserPersona reviewer = addPersona(accountA.userId(), UserPersonaType.REVIEWER, false);

        patchProfile(login(accountA.email()), "Ana", "America/Sao_Paulo",
                List.of("WRITER", "REVIEWER"), "WRITER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas", hasSize(2)));

        assertThat(personas(accountA.userId())).extracting(UserPersona::getId)
                .contains(reviewer.getId())
                .doesNotContain(editor.getId());
    }

    @Test
    void patchChangesPrimaryAgainstTheRealPartialUniqueIndex() throws Exception {
        UserPersona editor = addPersona(accountA.userId(), UserPersonaType.EDITOR, false);

        patchProfile(login(accountA.email()), "Ana", "America/Sao_Paulo",
                List.of("WRITER", "EDITOR"), "EDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personas[?(@.primary == true)]", hasSize(1)))
                .andExpect(jsonPath("$.personas[?(@.type == 'EDITOR' && @.primary == true)]", hasSize(1)));

        List<UserPersona> saved = personas(accountA.userId());
        assertThat(saved).filteredOn(UserPersona::isPrimary).singleElement()
                .extracting(UserPersona::getId).isEqualTo(editor.getId());
    }

    @Test
    void emptyPersonaSetIsRejectedWithoutPartialChanges() throws Exception {
        assertInvalidAndUnchanged(List.of(), "WRITER", "Novo nome", "America/Fortaleza");
    }

    @Test
    void duplicatePersonaIsRejectedWithoutPartialChanges() throws Exception {
        assertInvalidAndUnchanged(List.of("WRITER", "WRITER"), "WRITER", "Novo nome", "America/Fortaleza");
    }

    @Test
    void primaryPersonaOutsideTheSetIsRejectedWithoutPartialChanges() throws Exception {
        assertInvalidAndUnchanged(List.of("EDITOR"), "WRITER", "Novo nome", "America/Fortaleza");
    }

    @Test
    void invalidPersonaValueIsRejectedWithoutPartialChanges() throws Exception {
        assertInvalidAndUnchanged(List.of("WRITER", "TRANSLATOR"), "WRITER", "Novo nome", "America/Fortaleza");
    }

    @Test
    void invalidDisplayNameAndTimeZoneUseTheRegistrationPolicies() throws Exception {
        MockHttpSession session = login(accountA.email());

        patchProfile(session, "Ana\u0000Silva", "America/Sao_Paulo", List.of("WRITER"), "WRITER")
                .andExpect(status().isBadRequest());
        patchProfile(session, "Ana", "GMT-03:00", List.of("WRITER"), "WRITER")
                .andExpect(status().isBadRequest());

        assertThat(findUser(accountA.userId()).getDisplayName()).isEqualTo("Ana");
        assertThat(findUser(accountA.userId()).getTimeZoneId()).isEqualTo("America/Sao_Paulo");
    }

    @Test
    void browserSuppliedIdentityCannotChangeAnotherUsersProfile() throws Exception {
        MockHttpSession session = login(accountA.email());
        Map<String, Object> body = Map.of(
                "displayName", "Ana atualizada",
                "timeZone", "America/Fortaleza",
                "personas", List.of("WRITER", "EDITOR"),
                "primaryPersona", "EDITOR",
                "userId", accountB.userId(),
                "tenantId", accountB.tenantId(),
                "role", "OWNER"
        );

        mockMvc.perform(withCsrf(patch("/api/profile")).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(accountA.email()));

        assertThat(findUser(accountA.userId()).getDisplayName()).isEqualTo("Ana atualizada");
        assertThat(findUser(accountB.userId()).getDisplayName()).isEqualTo("Bruno");
        assertThat(personas(accountB.userId())).extracting(UserPersona::getPersona)
                .containsExactly(UserPersonaType.REVIEWER);
    }

    @Test
    void addingEditorPersonaDoesNotGrantAccessToAnInaccessibleBook() throws Exception {
        Book foreignBook = createBookOwnedBy(accountA, "Livro privado de Ana");
        MockHttpSession brunoSession = login(accountB.email());

        mockMvc.perform(get("/api/books/{bookId}", foreignBook.getId()).session(brunoSession))
                .andExpect(status().isNotFound());

        patchProfile(brunoSession, "Bruno", "UTC", List.of("REVIEWER", "EDITOR"), "EDITOR")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/books/{bookId}", foreignBook.getId()).session(brunoSession))
                .andExpect(status().isNotFound());
    }

    private void assertInvalidAndUnchanged(
            List<String> requestedPersonas,
            String primaryPersona,
            String displayName,
            String timeZone
    ) throws Exception {
        patchProfile(login(accountA.email()), displayName, timeZone, requestedPersonas, primaryPersona)
                .andExpect(status().isBadRequest());

        User unchanged = findUser(accountA.userId());
        assertThat(unchanged.getDisplayName()).isEqualTo("Ana");
        assertThat(unchanged.getTimeZoneId()).isEqualTo("America/Sao_Paulo");
        assertThat(personas(accountA.userId())).singleElement().satisfies(persona -> {
            assertThat(persona.getPersona()).isEqualTo(UserPersonaType.WRITER);
            assertThat(persona.isPrimary()).isTrue();
        });
    }

    private org.springframework.test.web.servlet.ResultActions patchProfile(
            MockHttpSession session,
            String displayName,
            String timeZone,
            List<String> requestedPersonas,
            String primaryPersona
    ) throws Exception {
        Map<String, Object> body = Map.of(
                "displayName", displayName,
                "timeZone", timeZone,
                "personas", requestedPersonas,
                "primaryPersona", primaryPersona
        );
        return mockMvc.perform(withCsrf(patch("/api/profile")).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private Account createAccount(
            String displayName,
            String emailPrefix,
            String timeZone,
            UserPersonaType primaryPersona
    ) {
        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(emailPrefix + "-" + UUID.randomUUID() + "@iwrite.local");
        user.setTimeZoneId(timeZone);
        entityManager.persist(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entityManager.persist(credential);

        Tenant tenant = new Tenant();
        tenant.setName("Espaço de " + displayName);
        tenant.setDefaultTimeZoneId(timeZone);
        entityManager.persist(tenant);

        TenantMembership membership = new TenantMembership();
        membership.setUser(user);
        membership.setTenant(tenant);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);

        addPersona(user.getId(), primaryPersona, true);
        entityManager.flush();
        return new Account(user.getId(), tenant.getId(), user.getEmail());
    }

    private UserPersona addPersona(UUID userId, UserPersonaType type, boolean primary) {
        UserPersona persona = new UserPersona();
        persona.setUserId(userId);
        persona.setPersona(type);
        persona.setPrimary(primary);
        entityManager.persist(persona);
        entityManager.flush();
        return persona;
    }

    private void revokeMembership(UUID userId) {
        membershipRepository.deleteAll(membershipRepository.findByUser_Id(userId));
        entityManager.flush();
        entityManager.clear();
    }

    private User findUser(UUID userId) {
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(User.class, userId);
    }

    private List<UserPersona> personas(UUID userId) {
        entityManager.flush();
        entityManager.clear();
        return entityManager.createQuery("select p from UserPersona p where p.userId = :userId order by p.createdAt", UserPersona.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    private Book createBookOwnedBy(Account owner, String title) {
        Book book = new Book();
        book.setTenant(entityManager.getReference(Tenant.class, owner.tenantId()));
        book.setOwner(entityManager.getReference(User.class, owner.userId()));
        book.setTitle(title);
        book.setStatus(BookStatus.PLANNING);
        entityManager.persist(book);
        entityManager.flush();
        return book;
    }

    private record Account(UUID userId, UUID tenantId, String email) {
    }
}
