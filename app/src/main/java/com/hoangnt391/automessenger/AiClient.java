package com.hoangnt391.automessenger;

/** AI facade: Poe is the only provider. */
public final class AiClient {
    private AiClient() {}
    public static String reply(String ignoredKey, String ignoredModel, String instructions, String incoming) throws Exception {
        MessageAccessibilityService service = MessageAccessibilityService.getInstance();
        if (service == null) throw new IllegalStateException("Chưa bật Trợ năng.");
        return service.requestPoeReply(instructions, incoming);
    }
    public static void validateKey(String ignoredKey, String ignoredModel) {}
}
