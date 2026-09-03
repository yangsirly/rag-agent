package yangsirly.rag_agent.authentication;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期清理已自然过期的 Refresh 会话，避免长期登录历史无限增长。 */
@Component
public class RefreshSessionCleanup {

    private final RefreshSessionService refreshSessionService;

    public RefreshSessionCleanup(RefreshSessionService refreshSessionService) {
        this.refreshSessionService = refreshSessionService;
    }

    @Scheduled(fixedDelayString = "${security.auth.refresh-session-cleanup-delay-ms:86400000}")
    public void deleteExpiredSessions() {
        refreshSessionService.deleteExpiredSessions();
    }
}
