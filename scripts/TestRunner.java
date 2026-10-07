import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** Run every supplied project test through the real JUnit Platform in one JVM. */
public final class TestRunner {
    public static void main(String[] args) throws Exception {
        var builder = LauncherDiscoveryRequestBuilder.request();
        for (int i = 1; i < args.length; i++) builder.selectors(DiscoverySelectors.selectClass(args[i]));
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(builder.build());
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out));
        summary.printFailuresTo(new PrintWriter(System.out));
        Files.writeString(Path.of(args[0]), "{\"tests\":" + summary.getTestsFoundCount()
                + ",\"successful\":" + summary.getTestsSucceededCount()
                + ",\"failed\":" + summary.getTotalFailureCount()
                + ",\"skipped\":" + summary.getTestsSkippedCount() + "}");
        System.exit(summary.getTotalFailureCount() == 0 && summary.getTestsFoundCount() > 0 ? 0 : 1);
    }
}
