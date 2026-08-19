import { Button, Empty, Result, Spin } from "antd";
import type { ReactNode } from "react";
import { t } from "@/shared/i18n";

type Props = {
  loading?: boolean;
  error?: string | null;
  empty?: boolean;
  emptyDescription?: string;
  onRetry?: () => void;
  children?: ReactNode;
};

export function PageState({
  loading,
  error,
  empty,
  emptyDescription,
  onRetry,
  children,
}: Props) {
  const i18n = t();
  if (loading) {
    return (
      <div style={{ display: "grid", placeItems: "center", minHeight: 240 }}>
        <Spin size="large" tip={i18n.common.loading} />
      </div>
    );
  }
  if (error) {
    return (
      <Result
        status="error"
        title={i18n.common.unknownError}
        subTitle={error}
        extra={
          onRetry ? (
            <Button type="primary" onClick={onRetry}>
              {i18n.common.retry}
            </Button>
          ) : null
        }
      />
    );
  }
  if (empty) {
    return <Empty description={emptyDescription ?? i18n.common.empty} />;
  }
  return <>{children}</>;
}
