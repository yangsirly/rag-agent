import { Button, Input } from "antd";
import { SendOutlined } from "@ant-design/icons";
import { useState } from "react";
import { unicodeLength } from "@/shared/lib/unicode";
import { t } from "@/shared/i18n";
import styles from "../pages/chat.module.css";

type Props = {
  disabled?: boolean;
  sending?: boolean;
  onSend: (content: string) => void;
};

export function Composer({ disabled, sending, onSend }: Props) {
  const i18n = t();
  const [value, setValue] = useState("");

  const submit = () => {
    const content = value;
    if (content.trim().length === 0) return;
    if (unicodeLength(content) > 10000) return;
    onSend(content);
    setValue("");
  };

  return (
    <div className={styles.composer}>
      <Input.TextArea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={i18n.chat.inputPlaceholder}
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={disabled || sending}
        onPressEnter={(e) => {
          if (!e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <Button
        type="primary"
        icon={<SendOutlined />}
        onClick={submit}
        loading={sending}
        disabled={disabled || value.trim().length === 0}
      >
        {i18n.chat.send}
      </Button>
    </div>
  );
}
