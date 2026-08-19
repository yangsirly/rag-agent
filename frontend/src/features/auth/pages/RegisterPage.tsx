import { Alert, Button, Card, Form, Input, Typography, message } from "antd";
import { Link, useNavigate } from "react-router-dom";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { registerApi } from "@/features/auth/api";
import { emailField, passwordField } from "@/shared/lib/validation";
import { AppApiError, mapRegisterError } from "@/shared/api/errors";
import { t } from "@/shared/i18n";
import styles from "./auth.module.css";

const schema = z.object({
  email: emailField,
  password: passwordField,
});

type FormValues = z.infer<typeof schema>;

export function RegisterPage() {
  const i18n = t();
  const navigate = useNavigate();

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: (values: FormValues) => registerApi(values.email, values.password),
    onSuccess: () => {
      message.success(i18n.auth.registerSuccess);
      navigate("/login", { replace: true });
    },
  });

  const errorMessage =
    mutation.error instanceof AppApiError
      ? mapRegisterError(mutation.error)
      : mutation.error
        ? i18n.common.unknownError
        : null;

  return (
    <div className={styles.page}>
      <Card className={styles.card} variant="borderless">
        <Typography.Title level={3} className={styles.title}>
          {i18n.auth.registerTitle}
        </Typography.Title>
        <Typography.Paragraph type="secondary">{i18n.auth.passwordHint}</Typography.Paragraph>
        {errorMessage ? <Alert type="error" showIcon message={errorMessage} style={{ marginBottom: 16 }} /> : null}
        <Form layout="vertical" onFinish={handleSubmit((v) => mutation.mutate(v))}>
          <Form.Item
            label={i18n.auth.email} htmlFor="register-email"
            validateStatus={errors.email ? "error" : undefined}
            help={errors.email?.message}
          >
            <Controller
              name="email"
              control={control}
              render={({ field }) => (
                <Input {...field} id="register-email" autoComplete="email" size="large" />
              )}
            />
          </Form.Item>
          <Form.Item
            label={i18n.auth.password} htmlFor="register-password"
            validateStatus={errors.password ? "error" : undefined}
            help={errors.password?.message}
          >
            <Controller
              name="password"
              control={control}
              render={({ field }) => (
                <Input.Password
                  {...field}
                  id="register-password"
                  autoComplete="new-password"
                  size="large"
                />
              )}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={mutation.isPending}>
            {i18n.auth.register}
          </Button>
        </Form>
        <div className={styles.footer}>
          <Link to="/login">{i18n.auth.goLogin}</Link>
        </div>
      </Card>
    </div>
  );
}
