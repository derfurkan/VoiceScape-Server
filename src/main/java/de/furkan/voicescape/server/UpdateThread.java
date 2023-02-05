package de.furkan.voicescape.server;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class UpdateThread implements Runnable {

  private ArrayList<String> lastRegistered = new ArrayList<>();

  public UpdateThread() {
    Thread thread = new Thread(this, "UpdateThread");
    thread.start();
  }

  @Override
  public void run() {
    new Timer()
        .schedule(
            new TimerTask() {
              @Override
              public void run() {

                Gson gson = new Gson();
                if (Core.getInstance().registeredPlayerSockets.equals(lastRegistered)) {
                  return;
                }
                Core.getInstance()
                    .sendToAllMessageThreads(
                        "register " + gson.toJson(Core.getInstance().registeredPlayerSockets));
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                Core.getInstance()
                    .sendToAllMessageThreads(
                        "unregister " + gson.toJson(Core.getInstance().unregisteredPlayerSockets));

                lastRegistered = new ArrayList<>(Core.getInstance().registeredPlayerSockets);
              }
            },
            1000,
            Core.getInstance().UPDATE_CLIENTS_INTERVAL_MS);
  }
}
