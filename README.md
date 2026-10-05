# AutoMessenger

Ứng dụng Android tự động hỗ trợ trả lời tin nhắn với **AshnaAI là AI duy nhất**.

## APK mới nhất
[⬇️ TẢI APK MỚI NHẤT](https://github.com/hoangnt391/AutoMessenger/releases/download/latest/app-debug.apk)

## Luồng AI
- Không còn ChatGPT.
- Không còn Gemini/fallback Gemini.
- Mỗi yêu cầu chỉ mở **một phiên AshnaAI**.
- Có khóa chống gửi/mở AshnaAI lặp khi một yêu cầu đang xử lý.
- Câu trả lời được đọc qua Accessibility rồi đưa về bong bóng.
- Bong bóng có lịch sử câu hỏi/câu trả lời, sao chép câu hỏi/câu trả lời, đưa câu trả lời vào ô chat và nút mở phần Cài đặt/Prompt/Trợ năng.

## Cài đặt
1. Cài AshnaAI.
2. Bật Trợ năng cho AutoMessenger.
3. Cấp quyền bong bóng nổi.
4. Bật tự động trả lời khi cần.

Không cần Gemini API key hoặc ChatGPT API key.


## AshnaAI

- AI provider: direct AshnaAI Chat Completions API.
- Base URL: https://api.ashna.ai/v1/api
- The app does not open an AI app/tab for each message.
- API key and model are entered in the app and stored locally; no API key is committed to this repository.
- Automatic replies keep the smart debounce: 5 seconds for an isolated message; if more messages arrive, the quiet period becomes 10 seconds and resets while messages continue.
- A burst is sent to AshnaAI as one request and the result is sent back through Accessibility.
