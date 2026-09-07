package com.crewpocket.helper;

/** Conservative target policy; describes executable controls, not user message text. */
final class ActionSafetyPolicy {
    static boolean blocks(String metadata) {
        String value = TextMatch.caseFold(metadata).replace('_', ' ').replace('-', ' ');
        return value.matches("(?s).*\\b(delete|erase|purchase|checkout|pay|payment|buy|transfer|password|otp|cvv)\\b.*")
                || value.matches("(?s).*(刪除|删除|付款|支付|購買|购买|轉帳|转账|匯款|汇款|修改帳戶|修改账户|密碼|密码|驗證碼|验证码).*");
    }
}
