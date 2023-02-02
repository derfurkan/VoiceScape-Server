package de.furkan.voicescape.server;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class MessageThread implements Runnable {

  private final Socket currentConnection;
  private final Thread currentThread;
  private final PrintWriter out;
  private final BufferedReader in;
  public VoiceThread currentVoiceThread;
  private boolean isRunning = true;
  private long lastMessage, messageCount, flags;

  public MessageThread(Socket currentConnection, VoiceThread currentVoiceThread) {
    this.currentVoiceThread = currentVoiceThread;
    this.currentThread = new Thread(this, "MessageThread");
    this.currentConnection = currentConnection;
    try {
      out = new PrintWriter(currentConnection.getOutputStream(), true);
      in = new BufferedReader(new InputStreamReader(currentConnection.getInputStream()));
    } catch (IOException e) {
      currentVoiceThread.stop();
      throw new RuntimeException(e);
    }
    this.currentThread.start();
  }

  @Override
  public void run() {
    try {

      String inputLine;
      while ((inputLine = in.readLine()) != null && isRunning) {
        /*    System.out.println(
        "["
            + currentVoiceThread.clientName
            + "/"
            + currentConnection.getInetAddress().getHostAddress()
            + "] Received: "
            + inputLine);*/
        messageCount++;

        if (lastMessage != 0L
            && System.currentTimeMillis() - lastMessage < Core.getInstance().MIN_MESSAGE_TIMEOUT_MS
            && messageCount >= 5) {
          flags++;
          new Timer()
              .schedule(
                  new TimerTask() {
                    @Override
                    public void run() {
                      flags--;
                    }
                  },
                  Core.getInstance().FLAG_REMOVE_TIMEOUT_MS);
          if (flags >= 3) {
            System.out.println(
                "["
                    + currentVoiceThread.clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Message spamming detected, disconnecting and blacklisting IP for "
                    + Core.getInstance().MESSAGE_SPAM_BLACKLIST_BAN_MS / 1000
                    + " seconds.");
            Core.getInstance()
                .blackListedSpamIPs
                .add(currentConnection.getInetAddress().getHostAddress());
            new Timer()
                .schedule(
                    new TimerTask() {
                      @Override
                      public void run() {
                        Core.getInstance()
                            .blackListedSpamIPs
                            .remove(currentConnection.getInetAddress().getHostAddress());
                        System.out.println(
                            "["
                                + currentVoiceThread.clientName
                                + "/"
                                + currentConnection.getInetAddress().getHostAddress()
                                + "] Removed from blacklist.");
                      }
                    },
                    Core.getInstance().MESSAGE_SPAM_BLACKLIST_BAN_MS);
            currentVoiceThread.stop();
            return;
          }
        }
        if (inputLine.startsWith("register:")) {
          String name = inputLine.replace("register:", "");
          Core.getInstance().registeredPlayerSockets.add(name);
          currentVoiceThread.clientName = name;
          System.out.println(
              "["
                  + currentVoiceThread.clientName
                  + "/"
                  + currentConnection.getInetAddress().getHostAddress()
                  + "] Registered");
          continue;
        }

        if (Core.getInstance().registeredPlayerSockets.contains(currentVoiceThread.clientName)) {
          if (inputLine.equalsIgnoreCase("disconnect")) {
            System.out.println(
                "["
                    + currentVoiceThread.clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Disconnected");
            currentVoiceThread.stop();
          } else if (inputLine.startsWith("mute") && inputLine.contains(" ")) {
            currentVoiceThread.mutedPlayers.add(inputLine.replace("mute ", ""));
            System.out.println(
                "["
                    + currentVoiceThread.clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Muted "
                    + inputLine.replace("mute ", ""));
          } else if (inputLine.startsWith("unmute") && inputLine.contains(" ")) {
            currentVoiceThread.mutedPlayers.remove(inputLine.replace("unmute ", ""));
            System.out.println(
                "["
                    + currentVoiceThread.clientName
                    + "/"
                    + currentConnection.getInetAddress().getHostAddress()
                    + "] Unmuted "
                    + inputLine.replace("unmute ", ""));
          } else {
            ArrayList<String> nearPlayers = new ArrayList<>();
            Gson gson = new Gson();
            nearPlayers = gson.fromJson(inputLine, nearPlayers.getClass());

            currentVoiceThread.nearPlayers.clear();
            currentVoiceThread.nearPlayers.addAll(nearPlayers);
          }
        }
        lastMessage = System.currentTimeMillis();
      }
    } catch (Exception e) {
      if (Core.getInstance().KILL_SOCKET_IF_INVALID_MESSAGE) {
        currentVoiceThread.stop();
      }
    }
  }

  public void stop() {
    if (!isRunning) return;
    try {
      isRunning = false;
      Core.getInstance().registeredPlayerSockets.remove(currentVoiceThread.clientName);
      Core.getInstance().voiceSockets.remove(currentVoiceThread);
      in.close();
      out.close();
      currentConnection.close();
      currentThread.interrupt();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
