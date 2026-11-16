package com.gearhub.marketplace;

/** CPF e CNPJ conferidos pelos dígitos verificadores, sem consultar serviço externo. */
public final class BrazilianDocuments {

    private BrazilianDocuments() {
    }

    /** Só os números: aceita o documento digitado com ou sem pontuação. */
    public static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    public static boolean isValidCpf(String value) {
        String cpf = digits(value);
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(cpf, 9, 10) == cpf.charAt(9) - '0' && checkDigit(cpf, 10, 11) == cpf.charAt(10) - '0';
    }

    private static int checkDigit(String cpf, int length, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (firstWeight - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    public static boolean isValidCnpj(String value) {
        String cnpj = digits(value);
        if (cnpj.length() != 14 || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        int[] first = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] second = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return cnpjDigit(cnpj, first) == cnpj.charAt(12) - '0' && cnpjDigit(cnpj, second) == cnpj.charAt(13) - '0';
    }

    private static int cnpjDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
