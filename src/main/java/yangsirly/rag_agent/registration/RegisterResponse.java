package yangsirly.rag_agent.registration;

/**
 * 注册成功时返回给客户端的数据结构。
 *
 * <p>record 是不可变的数据载体，Java 会自动生成构造器和 {@code statusCode()} 访问方法；
 * Spring 会把该对象序列化为 JSON。</p>
 *
 * @param statusCode 业务响应体中的状态码；真正的 HTTP 状态码由 ResponseEntity 设置
 */
public record RegisterResponse(int statusCode) {
}
