import { Alert, Button, Card, Form, Input, Typography } from "antd";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { loginApi, meApi } from "@/features/auth/api";
import { useAuthStore } from "@/features/auth/auth-store";
import { emailField, passwordField } from "@/shared/lib/validation";
import { AppApiError, mapLoginError } from "@/shared/api/errors";
import { t } from "@/shared/i18n";
import styles from "./auth.module.css";

const schema = z.object({
  email: emailField,
  password: passwordField,
});

type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const i18n = t();
  const navigate = useNavigate();
  const location = useLocation();
  const setUser = useAuthStore((s) => s.setUser);
  const queryClient = useQueryClient();
  const from = (location.state as { from?: string } | null)?.from ?? "/chat";

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: (values: FormValues) => loginApi(values.email, values.password),
    onSuccess: async () => {
      // 登录前的 bootstrap /me 可能仍在飞行中；取消它，避免迟到的匿名 401
      // 触发全局登出处理覆盖刚建立的登录态。
      await queryClient.cancelQueries({ queryKey: ["auth", "me"] });
      // 登录响应只有 role；再拉 /me 拿完整用户信息，保证刷新与导航一致
      const me = await meApi();
      setUser({ userId: me.userId, email: me.email, role: me.role });
      // 把 bootstrap 查询直接置为成功，避免登录前的匿名 401 迟到后把用户清空；
      // 其余业务查询仍按原行为失效，进入页面后会按新身份重新加载。
      queryClient.setQueryData(["auth", "me"], me);
      await queryClient.invalidateQueries({
        predicate: (query) => query.queryKey[0] !== "auth",
      });
      navigate(from, { replace: true });
    },
  });

  const errorMessage =
    mutation.error instanceof AppApiError ? mapLoginError(mutation.error) : mutation.error ? i18n.common.unknownError : null;

  return (
    <div className={styles.page}>
      <Card className={styles.card} variant="borderless">
        <Typography.Title level={3} className={styles.title}>
          {i18n.auth.loginTitle}
        </Typography.Title>
        <Typography.Paragraph type="secondary">{i18n.appSubtitle}</Typography.Paragraph>
        {errorMessage ? <Alert type="error" showIcon message={errorMessage} style={{ marginBottom: 16 }} /> : null}
        <Form layout="vertical" onFinish={handleSubmit((v) => mutation.mutate(v))}>
          <Form.Item
            label={i18n.auth.email} htmlFor="login-email"
            validateStatus={errors.email ? "error" : undefined}
            help={errors.email?.message}
          >
            <Controller
              name="email"
              control={control}
              render={({ field }) => (
                <Input {...field} id="login-email" autoComplete="email" size="large" />
              )}
            />
          </Form.Item>
          <Form.Item
            label={i18n.auth.password} htmlFor="login-password"
            validateStatus={errors.password ? "error" : undefined}
            help={errors.password?.message}
          >
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password
                  {...field}
                  id="login-password"
                  autoComplete="current-password"
                  size="large"
                />
              )}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={mutation.isPending}>
            {i18n.auth.login}
          </Button>
        </Form>
        <div className={styles.footer}>
          <Link to="/register">{i18n.auth.goRegister}</Link>
        </div>
      </Card>
    </div>
  );
}
