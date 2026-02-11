package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoteBot extends TelegramLongPollingBot {

    // ⚠ Заміни на числовий chatId твоєї групи
    private static final String GROUP_CHAT_ID = "-1003860160178";

    // Список userId адміністраторів, які можуть запускати опитування
    private static final Set<Long> ADMIN_IDS = Set.of(
            875558201L,  // твій ID
            636575553L   // інший адміністратор
    );

    private final Map<Long, String> votes = new HashMap<>();
    private final List<String> options = new ArrayList<>();
    private Integer messageIdWithPoll = null;

    // -------------------- BOT CONFIG --------------------
    @Override
    public String getBotUsername() {
        return "PavlogradVoteBot"; // username бота без @
    }

    @Override
    public String getBotToken() {
        return "8529535908:AAGghyNIcLwiHhJ4XKSSeDGeS5mPK9sIp4M"; // токен бота
    }
    // -----------------------------------------------------

    @Override
    public void onUpdateReceived(Update update) {

        // --- Команди у приваті ---
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();
            Long userId = update.getMessage().getFrom().getId();

            if (text.startsWith("/startpoll")) {

                if (!ADMIN_IDS.contains(userId)) {
                    sendMessage(chatId, "Тільки адміністратори можуть запускати опитування.");
                    return;
                }

                String[] parts = text.split(" ", 2);
                if (parts.length < 2) {
                    sendMessage(chatId,
                            "Вкажіть варіанти через крапку з комою:\n/startpoll Варіант1;Варіант2;Варіант3");
                    return;
                }

                options.clear();
                for (String option : parts[1].split(";")) {
                    options.add(option.trim());
                }

                votes.clear();
                sendVoteButtons(GROUP_CHAT_ID); // публікуємо у групу
                sendMessage(chatId, "Опитування запущено ✅ (результати в групі)");
            }
        }

        // --- Голоси через кнопки ---
        if (update.hasCallbackQuery()) {
            Long userId = update.getCallbackQuery().getFrom().getId();
            String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
            String data = update.getCallbackQuery().getData();

            if (votes.containsKey(userId)) {
                sendMessage(chatId, "Ви вже проголосували ✅");
                return;
            }

            votes.put(userId, data);
            sendMessage(chatId, "Ваш голос прийнято: " + data);
        }
    }

    // Створення кнопок голосування
    private void sendVoteButtons(String chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String option : options) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(option);
            button.setCallbackData(option);
            rows.add(Collections.singletonList(button)); // вертикально
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        SendMessage message = new SendMessage(chatId, "Оберіть варіант:");
        message.setReplyMarkup(markup);

        try {
            var sentMessage = execute(message);
            messageIdWithPoll = sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Оновлення результатів
    private void updateResults() {
        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votes.values()) {
            counts.put(vote, counts.getOrDefault(vote, 0) + 1);
        }

        StringBuilder sb = new StringBuilder("Результати голосування:\n");
        for (String option : options) {
            sb.append(option).append(": ").append(counts.getOrDefault(option, 0)).append(" голосів\n");
        }

        sendMessage(GROUP_CHAT_ID, sb.toString()); // просто нове повідомлення
    }


    // Відправка простого повідомлення
    private void sendMessage(String chatId, String text) {
        try {
            execute(new SendMessage(chatId, text));
        } catch (TelegramApiException ignored) {}
    }

    // -------------------- MAIN --------------------
    public static void main(String[] args) throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        VoteBot bot = new VoteBot();
        botsApi.registerBot(bot);

        // Авто-оновлення результатів кожні 30 секунд
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(bot::updateResults, 30, 30, TimeUnit.SECONDS);
    }
}