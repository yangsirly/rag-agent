package yangsirly.rag_agent.knowledge;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/**
 * 知识库业务逻辑服务。
 */
@Service
public class KnowledgeBaseService {

    private static final int MIN_NAME_LENGTH = 1;
    private static final int MAX_NAME_LENGTH = 16;
    private static final int MAX_DESCRIPTION_LENGTH = 100;
    private static final int MAX_PAGE_INDEX = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public KnowledgeBaseService(
            KnowledgeBaseMapper knowledgeBaseMapper,
            DocumentMapper documentMapper,
            UserMapper userMapper,
            Clock clock) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    /**
     * 创建知识库。
     */
    public KnowledgeBaseEntity create(Long creatorId, CreateKnowledgeBaseRequest request) {
        validateUserExists(creatorId);

        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("请求参数非法");
        }

        String strippedName = request.name().strip();
        int nameLength = strippedName.codePointCount(0, strippedName.length());
        if (nameLength < MIN_NAME_LENGTH || nameLength > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("请求参数非法");
        }

        String strippedDesc = null;
        if (request.description() != null && !request.description().isBlank()) {
            strippedDesc = request.description().strip();
            int descLength = strippedDesc.codePointCount(0, strippedDesc.length());
            if (descLength > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("请求参数非法");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(creatorId, strippedName, strippedDesc, now);

        knowledgeBaseMapper.insert(entity);
        return entity;
    }

    /**
     * 获取单个知识库详情。
     */
    public KnowledgeBaseEntity getDetail(Long creatorId, Long id) {
        validateUserExists(creatorId);
        if (id == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        KnowledgeBaseEntity entity = knowledgeBaseMapper.findByIdAndCreatorId(id, creatorId);
        if (entity == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        return entity;
    }

    /**
     * 分页查询当前用户的有效知识库列表。
     */
    public KnowledgeBasePage list(Long creatorId, int page, int size) {
        validateUserExists(creatorId);

        if (page < 0 || page >= MAX_PAGE_INDEX) {
            throw new IllegalArgumentException("请求参数非法");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("请求参数非法");
        }

        int offset = page * size;
        List<KnowledgeBaseEntity> items = knowledgeBaseMapper.listByCreatorId(creatorId, offset, size);
        long totalElements = knowledgeBaseMapper.countByCreatorId(creatorId);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        return new KnowledgeBasePage(items, page, size, totalElements, totalPages);
    }

    /**
     * 修改知识库（名称或描述）。
     */
    public KnowledgeBaseEntity update(Long creatorId, Long id, UpdateKnowledgeBaseRequest request) {
        validateUserExists(creatorId);
        if (id == null || request == null || (request.name() == null && request.description() == null)) {
            throw new IllegalArgumentException("请求参数非法");
        }

        KnowledgeBaseEntity entity = knowledgeBaseMapper.findByIdAndCreatorId(id, creatorId);
        if (entity == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        boolean changed = false;

        if (request.name() != null) {
            String strippedName = request.name().strip();
            int nameLength = strippedName.codePointCount(0, strippedName.length());
            if (nameLength < MIN_NAME_LENGTH || nameLength > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("请求参数非法");
            }
            entity.setName(strippedName);
            changed = true;
        }

        if (request.description() != null) {
            String strippedDesc = request.description().strip();
            if (!strippedDesc.isEmpty()) {
                int descLength = strippedDesc.codePointCount(0, strippedDesc.length());
                if (descLength > MAX_DESCRIPTION_LENGTH) {
                    throw new IllegalArgumentException("请求参数非法");
                }
                entity.setDescription(strippedDesc);
            } else {
                entity.setDescription(null);
            }
            changed = true;
        }

        if (changed) {
            entity.setUpdatedAt(LocalDateTime.now(clock));
            knowledgeBaseMapper.updateById(entity);
        }

        return entity;
    }

    /**
     * 软删除知识库。
     */
    public void delete(Long creatorId, Long id) {
        validateUserExists(creatorId);
        if (id == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int affected = knowledgeBaseMapper.softDeleteById(id, now);
        if (affected == 0) {
            throw new IllegalArgumentException("请求参数非法");
        }
        // 级联软删除知识库下的全部有效文档
        documentMapper.softDeleteByKnowledgeBaseId(id, now);
    }

    private void validateUserExists(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
    }

    public record KnowledgeBasePage(
            List<KnowledgeBaseEntity> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
