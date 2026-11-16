package io.deltaflow.audit.service;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for sanitizing sensitive financial information and primary account numbers (PAN)
 * to comply with PCI-DSS Requirements 3.3 and 10.2.
 */
@Service
public class PciDssMaskingService {

    // Matches PAN numbers (13 to 19 contiguous or dash/space-separated digits)
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    // Matches sensitive security fields in JSON: passwords, secrets, cvv, pin, tokens
    private static final Pattern SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "(\"(?:password|secret|token|apiKey|authorization|cvv|cvc|pin|apiKey|authCode)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE
    );

    // Matches explicit account number fields in JSON: "sourceAccount": "1234567890123456"
    private static final Pattern ACCOUNT_FIELD_PATTERN = Pattern.compile(
            "(\"(?:sourceAccount|targetAccount|accountNumber|cardNumber|pan)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE
    );

    public String mask(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        // 1. Redact credentials/passwords/cvv
        String result = SENSITIVE_FIELD_PATTERN.matcher(payload).replaceAll("$1[REDACTED]$3");

        // 2. Mask JSON account fields specifically
        Matcher accountMatcher = ACCOUNT_FIELD_PATTERN.matcher(result);
        StringBuilder accountSb = new StringBuilder();
        while (accountMatcher.find()) {
            String prefix = accountMatcher.group(1);
            String rawVal = accountMatcher.group(2);
            String suffix = accountMatcher.group(3);

            if (rawVal.startsWith("****-****-****-")) {
                accountMatcher.appendReplacement(accountSb, Matcher.quoteReplacement(prefix + rawVal + suffix));
            } else {
                String masked = maskAccountNumber(rawVal);
                accountMatcher.appendReplacement(accountSb, Matcher.quoteReplacement(prefix + masked + suffix));
            }
        }
        accountMatcher.appendTail(accountSb);
        result = accountSb.toString();

        // 3. Mask any remaining 13-19 digit PAN strings
        Matcher panMatcher = PAN_PATTERN.matcher(result);
        StringBuilder panSb = new StringBuilder();
        while (panMatcher.find()) {
            String match = panMatcher.group();
            String digitsOnly = match.replaceAll("[^0-9]", "");
            if (digitsOnly.length() >= 13 && digitsOnly.length() <= 19) {
                String last4 = digitsOnly.substring(digitsOnly.length() - 4);
                String masked = "****-****-****-" + last4;
                panMatcher.appendReplacement(panSb, Matcher.quoteReplacement(masked));
            } else {
                panMatcher.appendReplacement(panSb, Matcher.quoteReplacement(match));
            }
        }
        panMatcher.appendTail(panSb);

        return panSb.toString();
    }

    private String maskAccountNumber(String account) {
        if (account == null || account.length() < 4) {
            return "****";
        }
        String digits = account.replaceAll("[^0-9a-zA-Z]", "");
        String last4 = (digits.length() >= 4) ? digits.substring(digits.length() - 4) : account;
        return "****-****-****-" + last4;
    }
}
