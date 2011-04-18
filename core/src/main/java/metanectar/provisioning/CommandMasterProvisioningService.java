package metanectar.provisioning;

import com.google.common.collect.Maps;
import hudson.*;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.LocalChannel;
import hudson.remoting.VirtualChannel;
import metanectar.model.MetaNectar;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A master provisioning service that executes commands to provision and terminate masters.
 * <p>
 * The command to provision a master must return a URL on stdout, if the command is successful.
 *
 * @author Paul Sandoz
 */
public class CommandMasterProvisioningService extends MasterProvisioningService {

    public enum Variable {
        MASTER_PORT,
        MASTER_HOME,
        MASTER_METANECTAR_ENDPOINT,
        MASTER_GRANT_ID
    }

    private final int basePort;

    private final String homeLocation;

    private final int timeOut;

    private final String provisionCommand;

    private final String terminateCommand;

    @DataBoundConstructor
    public CommandMasterProvisioningService(int basePort, String homeLocation, int timeOut, String provisionCommand, String terminateCommand) {
        this.basePort = basePort;
        this.homeLocation = homeLocation;
        this.timeOut = timeOut;
        this.provisionCommand = provisionCommand;
        this.terminateCommand = terminateCommand;
    }

    public int getBasePort() {
        return basePort;
    }

    public String getHomeLocation() {
        return homeLocation;
    }

    public int getTimeOut() {
        return timeOut;
    }

    public String getProvisionCommand() {
        return provisionCommand;
    }

    public String getTerminateCommand() {
        return terminateCommand;
    }

    private String getHome(String organization) {
        return (homeLocation.endsWith("/"))
                ? homeLocation + organization : homeLocation + "/" + organization;
    }

    private int getPort(int id) {
        return basePort + id;
    }

    @Override
    public Future<Master> provision(final VirtualChannel channel, final TaskListener listener,
                                    final int id, final String organization, final URL metaNectarEndpoint, final Map<String, Object> properties) throws Exception {
        final String home = getHome(organization);

        final Map<String, String> variables = Maps.newHashMap();
        variables.put(Variable.MASTER_PORT.toString(), Integer.toString(getPort(id)));
        variables.put(Variable.MASTER_HOME.toString(), home);
        variables.put(Variable.MASTER_METANECTAR_ENDPOINT.toString(), metaNectarEndpoint.toExternalForm());
        variables.put(Variable.MASTER_GRANT_ID.toString(), (String)properties.get(MetaNectar.GRANT_PROPERTY));

        return Computer.threadPoolForRemoting.submit(new Callable<Master>() {
            public Master call() throws Exception {
                HomeDirectoryProvisioner hdp = new HomeDirectoryProvisioner(getFilePath(channel, home));

                final ByteArrayOutputStream baos = new ByteArrayOutputStream();

                final Proc proc = getLauncher(channel, listener).launch().
                        envs(variables).
                        cmds(Util.tokenize(getProvisionCommand())).
                        stderr(listener.getLogger()).
                        stdout(baos).
                        start();

                final int result = proc.joinWithTimeout(timeOut, TimeUnit.SECONDS, listener);

                if (result != 0) {
                    throw new IOException(
                            "Failed to provision master, received signal from provision command: " + result);
                }

                final URL endpoint = new URL(new String(baos.toByteArray()));
                return new Master(organization, endpoint);
            }
        });
    }

    @Override
    public Future<?> terminate(final VirtualChannel channel, final TaskListener listener,
                               final String organization, boolean clean) throws Exception {
        final Map<String, String> variables = Maps.newHashMap();
        variables.put(Variable.MASTER_HOME.toString(), getHome(organization));

        return Computer.threadPoolForRemoting.submit(new Callable<Void>() {
            public Void call() throws Exception {

                final Proc proc = getLauncher(channel, listener).launch().
                        envs(variables).
                        cmds(Util.tokenize(getTerminateCommand())).
                        stderr(listener.getLogger()).
                        stdout(listener.getLogger()).
                        start();

                final int result = proc.joinWithTimeout(timeOut, TimeUnit.SECONDS, listener);

                if (result != 0) {
                    throw new IOException(
                            "Failed to terminate master, received signal from terminate command: " + result);
                }

                return null;
            }
        });
    }

    @Override
    public Map<String, Master> getProvisioned(VirtualChannel channel) {

        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    private Launcher getLauncher(final VirtualChannel channel, final TaskListener listener) throws Exception {
        return (channel instanceof LocalChannel)
                ? new Launcher.LocalLauncher(listener, channel)
                : new Launcher.RemoteLauncher(listener, channel, channel.call(new IsUnix()));
    }

    private FilePath getFilePath(final VirtualChannel channel, String path) {
        return (channel instanceof LocalChannel)
                ? new FilePath(new File(path))
                : new FilePath(channel, path);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<MasterProvisioningService> {
        public String getDisplayName() {
            return "Command Provisioning Service";
        }
    }

    private static final class IsUnix implements hudson.remoting.Callable<Boolean,IOException> {
        public Boolean call() throws IOException {
            return File.pathSeparatorChar==':';
        }
        private static final long serialVersionUID = 1L;
    }

}
