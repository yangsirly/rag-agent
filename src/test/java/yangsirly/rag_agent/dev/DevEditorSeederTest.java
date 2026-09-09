package yangsirly.rag_agent.dev;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import yangsirly.rag_agent.registration.User;
import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevEditorSeederTest {
    private final UserMapper users = mock(UserMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private MockEnvironment environment() {
        MockEnvironment env = new MockEnvironment() {
            @Override public Map<String, Object> getSystemEnvironment() {
                return Map.of("APP_DEV_EDITOR_SEED_EMAIL", " Editor@example.com ",
                              "APP_DEV_EDITOR_SEED_PASSWORD", "password1");
            }
        };
        env.setActiveProfiles("local");
        return env;
    }

    @Test void createsActiveEditorWithHashedPassword() {
        when(users.insert(any(UserEntity.class))).thenAnswer(call -> {
            UserEntity user = call.getArgument(0);
            assertEquals("editor@example.com", user.getEmail());
            assertEquals(User.Role.EDITOR, user.getRole());
            assertEquals(User.Status.ACTIVE, user.getStatus());
            assertNotEquals("password1", user.getPasswordHash());
            assertTrue(encoder.matches("password1", user.getPasswordHash()));
            return 1;
        });
        new DevEditorSeeder(users, encoder, environment()).run(null);
        verify(users).insert(any(UserEntity.class));
    }

    @Test void existingEditorIsIdempotent() {
        when(users.findByEmail("editor@example.com")).thenReturn(UserEntity.from(
            new User("editor@example.com", null, encoder.encode("password1"), User.Role.EDITOR, User.Status.ACTIVE)));
        new DevEditorSeeder(users, encoder, environment()).run(null);
        verify(users, never()).insert(any(UserEntity.class));
    }

    @Test void rejectsCustomerAndPasswordMismatchWithoutWrites() {
        for (User.Role role : User.Role.values()) {
            when(users.findByEmail("editor@example.com")).thenReturn(UserEntity.from(
                new User("editor@example.com", null, encoder.encode("different"), role, User.Status.ACTIVE)));
            assertThrows(IllegalStateException.class, () -> new DevEditorSeeder(users, encoder, environment()).run(null));
        }
        verify(users, never()).insert(any(UserEntity.class));
    }

    @Test void refusesMixedProfiles() {
        MockEnvironment env = environment();
        env.setActiveProfiles("local", "prod");
        assertThrows(IllegalStateException.class, () -> new DevEditorSeeder(users, encoder, env).run(null));
        verifyNoInteractions(users);
    }

    @Test void beanIsAbsentUnlessLocalAndExplicitlyEnabled() {
        ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DevEditorSeeder.class);
        context.run(c -> assertFalse(c.containsBean("devEditorSeeder")));
        context.withPropertyValues("spring.profiles.active=local").run(c ->
            assertFalse(c.containsBean("devEditorSeeder")));
        context.withPropertyValues("spring.profiles.active=prod", "app.dev.editor-seed.enabled=true").run(c ->
            assertFalse(c.containsBean("devEditorSeeder")));
        context.withPropertyValues("spring.profiles.active=local", "app.dev.editor-seed.enabled=true")
            .withBean(UserMapper.class, () -> users)
            .withBean(org.springframework.security.crypto.password.PasswordEncoder.class, () -> encoder)
            .run(c -> assertNotNull(c.getBean(DevEditorSeeder.class)));
    }
}
