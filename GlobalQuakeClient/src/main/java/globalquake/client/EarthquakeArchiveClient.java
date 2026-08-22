package globalquake.client;

import org.tinylog.Logger;

import globalquake.core.archive.ArchivedEvent;
import globalquake.core.archive.EarthquakeArchive;
import globalquake.core.archive.ArchivedQuake;
import globalquake.core.earthquake.data.Earthquake;
import globalquake.core.earthquake.quality.QualityClass;
import gqserver.api.Packet;
import gqserver.api.data.earthquake.ArchivedQuakeData;
import gqserver.api.packets.earthquake.ArchivedQuakePacket;

public class EarthquakeArchiveClient extends EarthquakeArchive {

    public void processPacket(ClientSocket ignoredSocket, Packet packet) {
        if(packet instanceof ArchivedQuakePacket quakePacket) {
            Logger.info("Received archived quake data for %s", quakePacket.archivedQuakeData().uuid());
            if(getArchivedQuakeByUUID(quakePacket.archivedQuakeData().uuid()) == null) {
                archiveQuake(quakePacket, null);
            }
        }
    }

    public void archiveQuake(ArchivedQuakePacket quakePacket, Earthquake earthquake){
        archiveQuake(createArchivedQuake(quakePacket), earthquake);
    }

    private ArchivedQuake createArchivedQuake(ArchivedQuakePacket quakePacket) {
        ArchivedQuakeData data = quakePacket.archivedQuakeData();
        ArchivedQuake archivedQuake = new ArchivedQuake(
                data.uuid(), data.lat(), data.lon(), data.depth(), data.magnitude(), data.origin(), QualityClass.values()[data.qualityID()], data.finalUpdateMillis()
        );

        quakePacket.archivedEventDataList().forEach(archivedEventData -> archivedQuake.getArchivedEvents().add(new ArchivedEvent(
                archivedEventData.lat(), archivedEventData.lon(), archivedEventData.maxRatio(), archivedEventData.pWave()
        )));

        return archivedQuake;
    }
}
