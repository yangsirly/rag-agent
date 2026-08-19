import { Button, Popconfirm } from "antd";
import { DeleteOutlined } from "@ant-design/icons";
import { t } from "@/shared/i18n";

type Props = {
  title: string;
  onConfirm: () => void | Promise<void>;
  loading?: boolean;
  danger?: boolean;
  label?: string;
  size?: "small" | "middle" | "large";
};

export function ConfirmDeleteButton({
  title,
  onConfirm,
  loading,
  label,
  size = "small",
}: Props) {
  const i18n = t();
  return (
    <Popconfirm title={title} onConfirm={onConfirm} okText={i18n.common.confirm} cancelText={i18n.common.cancel}>
      <Button danger size={size} icon={<DeleteOutlined />} loading={loading}>
        {label ?? i18n.common.delete}
      </Button>
    </Popconfirm>
  );
}
