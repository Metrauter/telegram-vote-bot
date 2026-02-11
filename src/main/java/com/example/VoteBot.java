package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
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

    // Заміни на свій чат (групу) - можна отримати @username або chatId
    private static final String GROUP_ID = "@futsal_pavlograd";
    private final Map<Long, String> votes = new HashMap<>();
    private Integer messageIdWithPoll = null; // повідомлення для оновлення результатів
    private final List<String> options = Arrays.asList("Команда A", "Команда B", "Команда C", "Команда D");

    @Override
    public String getBotUsername() {
        return System.getenv("PavlogradVoteBot");
    }

    @Override
    public String getBotToken() {
        return System.getenv("8529535908:AAGghyNIcLwiHhJ4XKSSeDGeS5mPK9sIp4M");
    }

    @Override
    public void onUpdateReceived(Update update) {

        // Обробка повідомлень (команд)
        if (update.hasMessage() && update.getMessage().hasText()) {
            String chatId = update.getMessage().getChatId().toString();
            String text = update.getMessage().getText();

            // Команда для старту нового опитування
            if (text.startsWith("/startpoll")) {
                // формат: /startpoll Команда A;Команда B;Команда C
                String[] parts = text.split(" ", 2);
                if (parts.length < 2) {
                    sendMessage(chatId, "Вкажіть варіанти через крапку з комою, наприклад:\n" +
                            "/startpoll Команда A;Команда B;Команда C");
                    return;
                }

                String[] newOptions = parts[1].split(";");
                options.clear();
                for (String option : newOptions) {
                    options.add(option.trim());
                }

                votes.clear(); // чистимо попередні голоси
                sendVoteButtons(GROUP_ID); // публікуємо голосування у групі
                sendMessage(chatId, "Опитування запущено ✅");
            }
        }

        // Обробка натискань кнопок
        if (update.hasCallbackQuery()) {
            Long userId = update.getCallbackQuery().getFrom().getId();
            String chatId = update.getCallbackQuery().getMessage().getChatId().toString();
            String data = update.getCallbackQuery().getData();

            if (!isUserSubscribed(userId)) {
                sendMessage(chatId, "Ви не підписані на канал!");
                return;
            }

            if (votes.containsKey(userId)) {
                sendMessage(chatId, "Ви вже проголосували ✅");
                return;
            }

            votes.put(userId, data);
            sendMessage(chatId, "Ваш голос прийнято: " + data);
        }
    }


    private boolean isUserSubscribed(Long userId) {
        try {
            GetChatMember getChatMember = new GetChatMember();
            getChatMember.setChatId(GROUP_ID);
            getChatMember.setUserId(userId);

            String status = execute(getChatMember).getStatus();
            return status.equals("member") ||
                    status.equals("administrator") ||
                    status.equals("creator");

        } catch (TelegramApiException e) {
            return false;
        }
    }

    private void sendVoteButtons(String chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String option : options) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(option);
            button.setCallbackData(option);
            rows.add(Collections.singletonList(button)); // вертикальні кнопки
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        SendMessage message = new SendMessage(chatId, "Оберіть команду:");
        message.setReplyMarkup(markup);

        try {
            var sentMessage = execute(message);
            messageIdWithPoll = sentMessage.getMessageId(); // зберігаємо ID повідомлення
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void updateResults() {
        if (messageIdWithPoll == null) return;

        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votes.values()) {
            counts.put(vote, counts.getOrDefault(vote, 0) + 1);
        }

        StringBuilder sb = new StringBuilder("Результати голосування:\n");
        for (String option : options) {
            sb.append(option).append(": ").append(counts.getOrDefault(option, 0)).append(" голосів\n");
        }

        EditMessageText edit = new EditMessageText();
        edit.setChatId(GROUP_ID);
        edit.setMessageId(messageIdWithPoll);
        edit.setText(sb.toString());

        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        VoteBot bot = new VoteBot();
        botsApi.registerBot(bot);

        // Запускаємо автоматичне оновлення результатів кожні 30 секунд
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(bot::updateResults, 30, 30, TimeUnit.SECONDS);

        // Стартове голосування
        bot.sendVoteButtons(GROUP_ID);
    }

    private void sendMessage(String chatId, String text) {
        try {
            execute(new SendMessage(chatId, text));
        } catch (TelegramApiException ignored) {}
    }
}
