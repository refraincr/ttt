package cn.governance;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

final class IdentityValues {
    private IdentityValues() {}
    static String clean(String value) { return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim(); }
    static boolean phone(String value) { return value.matches("1[3-9][0-9]{9}"); }
    static boolean card(String value) {
        if (!value.matches("[1-9][0-9]{16}[0-9X]")) return false;
        try {
            var birth = LocalDate.parse(value.substring(6,14), DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT));
            if (birth.isAfter(LocalDate.now()) || birth.getYear() < 1800) return false;
        } catch (RuntimeException ex) { return false; }
        int[] weights = {7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (value.charAt(i)-'0')*weights[i];
        return "10X98765432".charAt(sum%11) == value.charAt(17);
    }
}
