package globalquake.core.events;

import globalquake.core.GlobalQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.events.specific.GlobalQuakeEvent;
import globalquake.core.events.specific.QuakeCreateEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.events.specific.QuakeUpdateEvent;
import globalquake.core.events.specific.SeedlinkEvent;
import org.tinylog.Logger;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
public class GlobalQuakeEventHandler {


    private Queue<GlobalQuakeEventListener> eventListeners;

    private ExecutorService defaultExecutor;
    private ExecutorService seedlinkExecutor;

    public GlobalQuakeEventHandler runHandler() {
        eventListeners = new ConcurrentLinkedQueue<>();
        defaultExecutor = Executors.newSingleThreadExecutor();
        seedlinkExecutor = Executors.newSingleThreadExecutor();
        return this;
    }

    public void stopHandler(){
        GlobalQuake.instance.stopService(defaultExecutor);
        GlobalQuake.instance.stopService(seedlinkExecutor);
        eventListeners.clear();
    }

    public void registerEventListener(GlobalQuakeEventListener eventListener){
        eventListeners.add(eventListener);
    }


    public void fireEvent(GlobalQuakeEvent event){
        getExecutorFor(event).submit(() -> {
            if(event.shouldLog()) {
                Logger.tag("Event").trace("Event fired: %s".formatted(event.toString()));
            }
            for (GlobalQuakeEventListener eventListener : eventListeners) {
                try {
                    event.run(eventListener);
                } catch (Exception e) {
                    Logger.error(e);
                }
            }
        });
    }

    private ExecutorService getExecutorFor(GlobalQuakeEvent event) {
        if(event instanceof SeedlinkEvent){
            return seedlinkExecutor;
        }

        return defaultExecutor;
    }

}
