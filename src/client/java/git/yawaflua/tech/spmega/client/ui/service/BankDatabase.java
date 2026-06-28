package git.yawaflua.tech.spmega.client.ui.service;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class BankDatabase {
    private final String jdbcUrl;

    public BankDatabase(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public void initialize() {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS cards (
                            card_id TEXT PRIMARY KEY,
                            card_token TEXT NOT NULL,
                            card_name TEXT,
                            card_number TEXT,
                            balance INTEGER NOT NULL DEFAULT 0,
                            owner_uuid TEXT,
                            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS transfer_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            sender_card_id TEXT NOT NULL,
                            receiver TEXT NOT NULL,
                            amount INTEGER NOT NULL,
                            comment TEXT,
                            balance_after INTEGER,
                            status TEXT NOT NULL,
                            created_at TEXT DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to initialize sqlite database", exception);
        }
    }

    public synchronized void upsertCardCredentials(String cardId, String cardToken) {
        String sql = """
                INSERT INTO cards(card_id, card_token, updated_at)
                VALUES(?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(card_id) DO UPDATE SET
                    card_token = excluded.card_token,
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cardId);
            statement.setString(2, cardToken);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to upsert card credentials", exception);
        }
    }

    public synchronized void updateCardMeta(String cardId, String cardName, String cardNumber, long balance, String ownerUuid) {
        String sql = """
                UPDATE cards
                SET card_name = ?, card_number = ?, balance = ?, owner_uuid = ?, updated_at = CURRENT_TIMESTAMP
                WHERE card_id = ?
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cardName);
            statement.setString(2, cardNumber);
            statement.setLong(3, balance);
            statement.setString(4, ownerUuid);
            statement.setString(5, cardId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to update card meta", exception);
        }
    }

    public synchronized void updateCardBalance(String cardId, long balance) {
        String sql = "UPDATE cards SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE card_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, balance);
            statement.setString(2, cardId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to update card balance", exception);
        }
    }

    public synchronized void deleteCard(String cardId) {
        String sql = "DELETE FROM cards WHERE card_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cardId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to delete card", exception);
        }
    }

    public synchronized List<StoredCard> loadCards() {
        String sql = "SELECT card_id, card_token, card_name, card_number, balance, owner_uuid FROM cards ORDER BY updated_at DESC";
        List<StoredCard> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(new StoredCard(
                        rs.getString("card_id"),
                        rs.getString("card_token"),
                        rs.getString("card_name"),
                        rs.getString("card_number"),
                        rs.getLong("balance"),
                        rs.getString("owner_uuid")
                ));
            }
            return result;
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to load cards", exception);
        }
    }

    public synchronized CardCredentials getCredentials(String cardId) {
        String sql = "SELECT card_id, card_token FROM cards WHERE card_id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cardId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new CardCredentials(rs.getString("card_id"), rs.getString("card_token"));
                }
            }
            return null;
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to get card credentials", exception);
        }
    }

    public synchronized void insertTransferHistory(
            String senderCardId,
            String receiver,
            long amount,
            String comment,
            Long balanceAfter,
            String status
    ) {
        String sql = """
                INSERT INTO transfer_history(sender_card_id, receiver, amount, comment, balance_after, status)
                VALUES(?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, senderCardId);
            statement.setString(2, receiver);
            statement.setLong(3, amount);
            statement.setString(4, comment);
            if (balanceAfter == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setLong(5, balanceAfter);
            }
            statement.setString(6, status);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to insert transfer history", exception);
        }
    }

    public synchronized List<LocalTransaction> loadTransferHistory(String cardId) {
        String sql = "SELECT receiver, amount, comment, status, created_at FROM transfer_history WHERE sender_card_id = ? ORDER BY id DESC";
        List<LocalTransaction> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cardId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new LocalTransaction(
                            rs.getString("receiver"),
                            rs.getLong("amount"),
                            rs.getString("comment"),
                            rs.getString("status"),
                            rs.getString("created_at")
                    ));
                }
            }
            return result;
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to load transfer history", exception);
        }
    }

    public record LocalTransaction(String receiver, long amount, String comment, String status, String createdAt) {
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}

