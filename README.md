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
- Automatic replies use a 3-second quiet period: every new incoming message resets the timer; a burst is sent to Ashna Web Free as one request.
- The app acts as the intermediary: read incoming chat -> aggregate -> wait 3s -> send context + latest messages to Ashna GPT-6.1 Sol -> extract only the final reply -> write into the original chat composer -> press Send.
- Messages generated and sent by AutoMessenger are suppressed from the incoming queue to prevent self-reply loops.
- If several messages arrive while Ashna is answering, they are held and processed as the next conversation turn.


## Ashna Web Free
AutoMessenger hỗ trợ điều khiển AshnaAI Web Free qua Android Accessibility; đăng nhập AshnaAI một lần trước khi bật tự động. WebView trung gian chạy ngầm, không chuyển màn hình khỏi ứng dụng chat khi xử lý.


- Ashna Web login overlay: full-screen touch/scroll enabled with a dedicated close button.
