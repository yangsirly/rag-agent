package yangsirly.rag_agent.registration;

/**
 * API 失败时返回的统一 JSON 结构。
 *
 * @param statusCode HTTP 状态码的数值形式，例如 400 或 501
 * @param code 供前端程序稳定判断错误类型的机器可读编码
 * @param message 供开发者或用户理解错误原因的文字说明
 */
public record ApiErrorResponse(int statusCode, String code, String message) {
}
