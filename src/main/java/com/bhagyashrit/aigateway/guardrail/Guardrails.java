package com.bhagyashrit.aigateway.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Lightweight, self-contained guardrails.
 *
 * <p>Two hooks: prompt-injection heuristics on the way in, PII redaction on
 * the way out. These are <em>heuristics</em> — fast, dependency-free, and
 * honest about what they catch (see README limitations). They mirror the
 * concepts in the {@code llm-eval-guardrails} repo without depending on it.
 */
@Component
public class Guardrails {

    // ---- prompt-injection heuristics (inbound) ----

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // Direct instruction override attempts.
            Pattern.compile("(?i)\\bignore\\s+(all\\s+)?(previous|prior|earlier|above)\\s+instructions?\\b"),
            Pattern.compile("(?i)\\bdisregard\\s+(all\\s+)?(previous|prior)\\s+instructions?\\b"),
            // Role hijacking.
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\b"),
            Pattern.compile("(?i)\\bnew\\s+system\\s+prompt\\b"),
            Pattern.compile("(?i)\\bact\\s+as\\s+if\\s+you\\s+(have|were)\\s+no\\b"),
            // Secret extraction.
            Pattern.compile("(?i)\\breveal\\s+(your|the)\\s+(system\\s+prompt|instructions|api\\s+key|secret)\\b"),
            Pattern.compile("(?i)\\bprint\\s+(your|the)\\s+system\\s+prompt\\b"),
            // Jailbreak framing.
            Pattern.compile("(?i)\\bdeveloper\\s+mode\\b"),
            Pattern.compile("(?i)\\bdo\\s+anything\\s+now\\b"),
            // Delimiter smuggling / encoded payloads.
            Pattern.compile("(?i)```\\s*(system|instruction)"),
            Pattern.compile("[A-Za-z0-9+/]{80,}={0,2}")
    );

    /** Returns human-readable descriptions of matched injection rules (empty = clean). */
    public List<String> scanPrompt(String text) {
        List<String> findings = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return findings;
        }
        for (int i = 0; i < INJECTION_PATTERNS.size(); i++) {
            if (INJECTION_PATTERNS.get(i).matcher(text).find()) {
                findings.add("injection-rule-" + (i + 1));
            }
        }
        return findings;
    }

    // ---- PII redaction (outbound) ----

    private record PiiRule(Pattern pattern, String replacement) {}

    private static final List<PiiRule> PII_RULES = List.of(
            new PiiRule(Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[REDACTED_EMAIL]"),
            new PiiRule(Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "[REDACTED_SSN]"),
            new PiiRule(Pattern.compile("\\b(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"), "[REDACTED_PHONE]"),
            new PiiRule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[REDACTED_AWS_KEY]"),
            new PiiRule(Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b"), "[REDACTED_CARD]")
    );

    /**
     * Redacts PII from model output. Credit-card candidates additionally pass a
     * Luhn check to cut false positives; non-Luhn digit runs are left alone.
     */
    public String redactPii(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (PiiRule rule : PII_RULES) {
            var m = rule.pattern().matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String candidate = m.group();
                if ("[REDACTED_CARD]".equals(rule.replacement()) && !luhnValid(candidate)) {
                    continue; // leave non-card digit runs untouched
                }
                m.appendReplacement(sb, rule.replacement());
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static boolean luhnValid(String candidate) {
        String digits = candidate.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
