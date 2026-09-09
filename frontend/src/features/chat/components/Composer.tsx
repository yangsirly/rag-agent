import { Alert, Button, Input } from "antd";
import { SendOutlined } from "@ant-design/icons";
import { useState } from "react";
import { unicodeLength } from "@/shared/lib/unicode";
import { t } from "@/shared/i18n";
import styles from "../pages/chat.module.css";

type Props = {
  disabled?: boolean;
  sending?: boolean;
  cooldown?: number;
  onSend: (content: string) => void;
};

export function Composer({ disabled, sending, cooldown = 0, onSend }: Props) {
  const i18n = t();
  const [value, setValue] = useState("");

  const submit = () => {
    if (disabled || sending || cooldown) return;
    const content = value;
    if (content.trim().length === 0) return;
    if (unicodeLength(content) > 10000) return;
    onSend(content);
    setValue("");
  };

  return (
    <div className={styles.composer}>
      <Input.TextArea
        aria-label="消息内容"
        count={{ show: true, max: 10000, strategy: unicodeLength }}
        status={unicodeLength(value) > 10000 ? "error" : undefined}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={i18n.chat.inputPlaceholder}
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={disabled || sending}
        onPressEnter={(e) => {
          if (!e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            submit();
          }
        }}
      />
      {unicodeLength(value) > 10000 ? (
        <Alert type="error" message="消息内容须为 1～10000 字" />
      ) : null}
      <Button
        type="primary"
        icon={<SendOutlined />}
        onClick={submit}
        loading={sending}
        disabled={
          disabled || cooldown > 0 || unicodeLength(value) > 10000 || value.trim().length === 0
        }
      >
        {cooldown ? cooldown + " 秒后可重试" : i18n.chat.send}
      </Button>
    </div>
  );
}
