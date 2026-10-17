package cn.governance;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class InputChecks {
    private InputChecks() {}
    static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    static String text(String value, int max, String field) {
        require(value != null && !value.isBlank() && value.trim().length() <= max, field + "必填且不能超过" + max + "字符");
        return value.trim();
    }
    static void password(String value) {
        require(value != null && value.length() >= 12 && value.getBytes(StandardCharsets.UTF_8).length <= 72,
                "密码至少12字符，UTF-8编码不超过72字节");
    }
    static void version(Long supplied, long actual) {
        require(supplied != null, "请提供版本号");
        if (supplied != actual) throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被修改，请刷新后重试");
    }
}
