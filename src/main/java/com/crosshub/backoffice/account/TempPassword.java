package com.crosshub.backoffice.account;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 임시 비밀번호. 헷갈리는 글자(0/O, 1/l/I)는 빼고, realm 비번 정책(영문·숫자·특수문자 각 1개 이상)을 통과하도록 만든다 */
final class TempPassword {
    private static final String LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIALS = "!@#$%*?";
    private static final String ALL = LETTERS + DIGITS + SPECIALS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TempPassword() {}

    static String generate() {
        List<Character> chars = new ArrayList<>();
        chars.add(pick(LETTERS));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIALS));
        while (chars.size() < 12) chars.add(pick(ALL));
        Collections.shuffle(chars, RANDOM);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }

    private static char pick(String s) { return s.charAt(RANDOM.nextInt(s.length())); }
}
