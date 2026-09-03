package yangsirly.rag_agent.authentication;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** Refresh 会话持久化边界，刷新时必须使用数据库行锁。 */
@Mapper
public interface RefreshSessionMapper extends BaseMapper<RefreshSessionEntity> {

    @Select("SELECT id, user_id, token_hash, current_access_jti, current_access_expires_at, "
            + "expires_at, created_at, last_used_at, revoked_at "
            + "FROM refresh_sessions WHERE id = #{id} FOR UPDATE")
    RefreshSessionEntity findByIdForUpdate(@Param("id") String id);

    @Update("UPDATE refresh_sessions SET revoked_at = #{revokedAt} "
            + "WHERE id = #{id} AND revoked_at IS NULL")
    int revokeById(@Param("id") String id, @Param("revokedAt") LocalDateTime revokedAt);

    @Delete("DELETE FROM refresh_sessions WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
