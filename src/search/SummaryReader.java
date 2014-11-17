package search;

import com.flagstone.transform.DoAction;
import com.flagstone.transform.Movie;
import com.flagstone.transform.MovieHeader;
import com.flagstone.transform.MovieTag;
import com.flagstone.transform.ShowFrame;
import com.flagstone.transform.action.Action;
import com.flagstone.transform.action.BasicAction;
import com.flagstone.transform.sound.SoundStreamBlock;
import com.flagstone.transform.sound.SoundStreamHead;
import gui.AbstractSwingWorker;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingWorker;
import listener.DomainType;
import listener.GuiListener;
import listener.Video;
import search.util.VideoSearch;
import str.Str;
import util.Connection;
import util.ConnectionException;
import util.Constant;
import util.IO;
import util.Regex;
import util.RunnableUtil;

public class SummaryReader extends AbstractSwingWorker {

    GuiListener guiListener;
    private Video video;
    long swfName;
    Map<Integer, String> movieParts = new ConcurrentHashMap<Integer, String>(8, 0.75f, 8);
    final AtomicBoolean failure = new AtomicBoolean();

    public SummaryReader(GuiListener guiListener, Video video) {
        this.guiListener = guiListener;
        this.video = video;
    }

    @Override
    protected Object doInBackground() {
        guiListener.summaryReadStarted();
        try {
            readSummary();
        } catch (Exception e) {
            if (!isCancelled()) {
                guiListener.error(e);
            }
        }
        guiListener.summaryReadStopped();
        workDone();
        return null;
    }

    public void readSummary() throws Exception {
        IO.fileOp(Constant.TEMP_DIR, IO.MK_DIR);
        swfName = Str.hashCode(video.ID);
        String swfSpeech = Constant.TEMP_DIR + swfName + Constant.SWF;
        String swfPage = Constant.TEMP_DIR + swfName + Constant.HTML;
        if ((new File(swfSpeech)).exists() && (new File(swfPage)).exists()) {
            browse(swfPage);
            return;
        }

        String br1 = "<br>", br2 = br1 + "\\s*+" + br1;
        String newSummary = Regex.match(video.summary, "Storyline:", br2);
        if (newSummary.isEmpty()) {
            newSummary = Regex.firstMatch(video.summary, "Genre:").isEmpty() ? Regex.match(video.summary, "<font[^>]++>", br2) : Regex.match(video.summary, br2,
                    br2);
        } else {
            newSummary = Regex.match(newSummary, br1, "\\z");
        }

        video.summary = Regex.replaceAll(Regex.replaceAll(newSummary, 468), 470);
        for (Entry<String, String> entry : Regex.badStrs.entrySet()) {
            String hexCode = entry.getKey();
            if (hexCode.charAt(0) == '&') {
                video.summary = Regex.replaceAll(video.summary, hexCode, entry.getValue());
            }
        }
        video.summary = Regex.replaceAll(Regex.replaceAll(Regex.htmlToPlainText(video.summary), 472), 339).trim();

        List<String> summaryParts = Regex.split(video.summary, Str.get(477), Integer.parseInt(Str.get(478)));
        Collection<MoviePartFinder> moviePartFinders = new ArrayList<MoviePartFinder>(8);
        int numSummaryParts = summaryParts.size();

        for (int i = 0; i < numSummaryParts; i++) {
            moviePartFinders.add(new MoviePartFinder(i, Str.get(474) + URLEncoder.encode(summaryParts.get(i), Constant.UTF8)));
        }

        RunnableUtil.runAndWaitFor(moviePartFinders);
        if (isCancelled() || failure.get()) {
            return;
        }

        convertMoviesToAudioClip().encodeToFile(new File(swfSpeech));

        String page = Str.get(479).replace(Str.get(480), swfSpeech).replace(Str.get(481), Regex.cleanWeirdChars(video.title) + " (" + video.year + ")"), imagePath;
        page = page.replace(Str.get(482), (new File(imagePath = Constant.CACHE_DIR + VideoSearch.imagePath(video))).exists() ? imagePath : Constant.PROGRAM_DIR
                + "noPosterBig.jpg");

        IO.write(swfPage, page);
        browse(swfPage);
    }

    private Movie convertMoviesToAudioClip() throws Exception {
        Movie audioClip = new Movie();
        int numMovieParts = movieParts.size();

        for (int i = 0; i < numMovieParts; i++) {
            boolean add = false;
            MovieTag prevTag = null;
            Movie movie = new Movie();
            File moviePart = new File(movieParts.get(i));
            movie.decodeFromFile(moviePart);
            for (MovieTag tag : movie.getObjects()) {
                if (!add) {
                    if (i == 0) {
                        add = true;
                    } else if (tag instanceof SoundStreamHead) {
                        add = true;
                        continue;
                    }
                }
                if (add && (tag instanceof SoundStreamBlock || tag instanceof ShowFrame || tag instanceof MovieHeader || tag instanceof SoundStreamHead)) {
                    if (!(prevTag instanceof ShowFrame) || !(tag instanceof ShowFrame)) {
                        audioClip.add(tag);
                    }
                    prevTag = tag;
                }
            }
            IO.fileOp(moviePart, IO.RM_FILE);
        }
        audioClip.add(new DoAction(Arrays.asList((Action) BasicAction.STOP, BasicAction.END)));
        audioClip.add(ShowFrame.getInstance());
        return audioClip;
    }

    private void browse(String swfPage) throws Exception {
        guiListener.browserNotification("summary", "read to you", DomainType.VIDEO_INFO);
        Connection.browseFile(swfPage);
    }

    private class MoviePartFinder extends SwingWorker<Object, Object> {

        private Integer partNumber;
        private String url;

        MoviePartFinder(Integer partNumber, String url) {
            this.partNumber = partNumber;
            this.url = url;
        }

        @Override
        protected Object doInBackground() throws Exception {
            try {
                if (failure.get()) {
                    return null;
                }
                String source = Connection.getSourceCode(url, DomainType.VIDEO_INFO);

                if (!Regex.firstMatch(source, 485).isEmpty()) {
                    Connection.removeFromCache(url);
                    throw new ConnectionException(Connection.error(url));
                }

                if (failure.get()) {
                    return null;
                }

                url = Regex.match(source, 475);
                String movie = Constant.TEMP_DIR + swfName + "_" + partNumber + Constant.SWF;
                if (failure.get()) {
                    return null;
                }
                Connection.saveData(url, movie, DomainType.VIDEO_INFO);
                if (failure.get()) {
                    return null;
                }
                movieParts.put(partNumber, movie);
            } catch (Exception e) {
                failure.set(true);
                if (!isCancelled()) {
                    guiListener.error(e);
                }
            }
            return null;
        }
    }
}
