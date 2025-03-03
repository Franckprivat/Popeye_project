package worker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.json.JSONObject;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

class Worker {

    public static void main(String[] args) {
        try {
            Jedis redis = connectToRedis(System.getenv("REDIS_HOST"));
            Connection dbConn = connectToDB();

            System.err.println("Watching vote queue");

            while (true) {
                String voteJSON = redis.blpop(0, "votes").get(1);
                JSONObject voteData = new JSONObject(voteJSON);
                String voterID = voteData.getString("voter_id");
                String vote = voteData.getString("vote");

                System.err.printf("Processing vote for '%s' by '%s'\n", vote, voterID);
                updateVote(dbConn, voterID, vote);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void updateVote(Connection dbConn, String voterID, String vote) throws SQLException {
        PreparedStatement insert = dbConn.prepareStatement("INSERT INTO votes (id, vote) VALUES (?, ?)");
        insert.setString(1, voterID);
        insert.setString(2, vote);

        try {
            insert.executeUpdate();
        } catch (SQLException e) {
            PreparedStatement update = dbConn.prepareStatement("UPDATE votes SET vote = ? WHERE id = ?");
            update.setString(1, vote);
            update.setString(2, voterID);
            update.executeUpdate();
        }
    }

    static Jedis connectToRedis(String host) {
        Jedis conn = new Jedis(host, 6379);

        while (true) {
            try {
                conn.keys("*");
                break;
            } catch (JedisConnectionException e) {
                System.err.println("Waiting for redis");
                sleep(1000);
            }
        }

        System.err.println("Connected to redis");
        return conn;
    }

    static Connection connectToDB() throws SQLException {
        Connection conn = null;

        try {

            Class.forName("org.postgresql.Driver");
            String url = "jdbc:postgresql://" + getEnv("POSTGRES_HOST", "db") + ':' + getEnv("POSTGRES_PORT", "5432")
                    + "/" + getEnv("POSTGRES_DB", "votes");

            while (conn == null) {
                try {
                    conn = DriverManager.getConnection(url, System.getenv("POSTGRES_USER"),
                            System.getenv("POSTGRES_PASSWORD"));
                } catch (SQLException e) {
                    System.err.println("Waiting for db");
                    sleep(1000);
                }
            }

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.err.println("Connected to db");
        return conn;
    }

    static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    static void sleep(long duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            System.exit(1);
        }
    }
}
