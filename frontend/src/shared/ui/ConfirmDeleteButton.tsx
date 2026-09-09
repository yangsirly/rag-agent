import { Button, Popconfirm } from "antd";
import { DeleteOutlined } from "@ant-design/icons";
import { t } from "@/shared/i18n";

type Props = {
  title: string;
  onConfirm: () => void | Promise<void>;
  loading?: boolean;
  danger?: boolean;
  label?: string;
  ariaLabel?: string;
  size?: "small" | "middle" | "large";
};

export function ConfirmDeleteButton({
  title,
  onConfirm,
  loading,
  label,
  ariaLabel,
  size = "small",
}: Props) {
  const i18n = t();
  return (
    <Popconfirm
      title={title}
      onConfirm={onConfirm}
      okText={i18n.common.confirm}
      cancelText={i18n.common.cancel}
    >
      <Button aria-label={ariaLabel} danger size={size} icon={<DeleteOutlined />} loading={loading}>
        {label ?? i18n.common.delete}
      </Button>
    </Popconfirm>
  );
}
