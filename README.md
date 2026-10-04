# AutoMessenger

Ứng dụng Android hỗ trợ tự động trả lời tin nhắn, giữ nguyên luồng gửi tin nhắn hiện tại và thay AiBot Gemini bằng **ứng dụng ChatGPT chính thức**.

## 📥 BUILD MỚI NHẤT

👉 **[⬇️ TẢI APK MỚI NHẤT](https://github.com/hoangnt391/AutoMessenger/releases/download/latest/app-debug.apk)**

Release cố định `latest` luôn được cập nhật bằng APK của lần build thành công mới nhất trên nhánh `main`.

[Trang Release mới nhất](https://github.com/hoangnt391/AutoMessenger/releases/tag/latest) · [GitHub Actions](https://github.com/hoangnt391/AutoMessenger/actions/workflows/android.yml)

## 🤖 Luồng AI mới

1. Nhập câu hỏi trong bong bóng chat.
2. AutoMessenger mở **ChatGPT** tại `https://chatgpt.com/?temporary-chat=true`.
3. Accessibility tự tìm ô nhập của ChatGPT, điền câu hỏi và bấm **Gửi**.
4. Đọc câu trả lời từ cây Accessibility, không dùng screenshot cho luồng ChatGPT.
5. Trả câu trả lời về bong bóng chat để sao chép hoặc đưa vào ô chat.
6. Luồng gửi tin nhắn sang Messenger/Zalo/WhatsApp/Telegram vẫn giữ nguyên: tìm ô nhập → điền nội dung → tìm nút Gửi → bấm Gửi.

## 🔐 Dữ liệu ảnh

- Luồng ChatGPT mới **không chụp và không lưu ảnh màn hình**.
- Không ghi ảnh ChatGPT ra file hay database.
- Câu hỏi/câu trả lời ChatGPT chỉ được giữ tạm trong phiên xử lý để hoàn thành luồng.

## ⚙️ Cấu hình

- Bật **Trợ năng** cho AutoMessenger.
- Bật quyền **bong bóng nổi**.
- Khi AutoMessenger yêu cầu, cấp quyền ghi màn hình nếu muốn dùng phần nhận diện màn hình hiện có.
- Không còn cần Gemini API key/model.

## ✨ Tính năng

- Bong bóng AI.
- Ô nhập câu hỏi và nút gửi trong bong bóng.
- Sao chép câu hỏi và câu trả lời.
- Đưa câu trả lời vào ô chat.
- Tự động tìm ô nhập và nút Gửi của ứng dụng chat.
- Prompt tùy chỉnh được lưu làm thiết lập của ứng dụng.

## 🛠️ Build

GitHub Actions tự build APK Debug sau mỗi thay đổi trên `main` và cập nhật Release `latest`. Chỉ giữ đường dẫn tải **bản mới nhất** trong README này.

**Repository:** https://github.com/hoangnt391/AutoMessenger