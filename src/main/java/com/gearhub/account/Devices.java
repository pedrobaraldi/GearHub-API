package com.gearhub.account;

import java.util.Locale;

/** Nome legível do aparelho a partir do User-Agent: "Chrome no Windows", "Safari no iPhone". */
final class Devices {

    private Devices() {
    }

    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Aparelho desconhecido";
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        String browser;
        // A ordem importa: Edge e Opera também dizem "chrome", e o Chrome também diz "safari"
        if (agent.contains("edg/")) {
            browser = "Edge";
        } else if (agent.contains("opr/") || agent.contains("opera")) {
            browser = "Opera";
        } else if (agent.contains("firefox/") || agent.contains("fxios")) {
            browser = "Firefox";
        } else if (agent.contains("chrome/") || agent.contains("crios")) {
            browser = "Chrome";
        } else if (agent.contains("safari/")) {
            browser = "Safari";
        } else {
            browser = "Navegador";
        }
        String system;
        if (agent.contains("iphone")) {
            system = "iPhone";
        } else if (agent.contains("ipad")) {
            system = "iPad";
        } else if (agent.contains("android")) {
            system = "Android";
        } else if (agent.contains("windows")) {
            system = "Windows";
        } else if (agent.contains("mac os")) {
            system = "macOS";
        } else if (agent.contains("linux")) {
            system = "Linux";
        } else {
            return browser;
        }
        return browser + " no " + system;
    }
}
