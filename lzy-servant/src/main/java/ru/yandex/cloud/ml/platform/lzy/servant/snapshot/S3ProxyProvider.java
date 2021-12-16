package ru.yandex.cloud.ml.platform.lzy.servant.snapshot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gaul.s3proxy.S3Proxy;
import org.gaul.shaded.org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import ru.yandex.cloud.ml.platform.lzy.servant.agents.LzyExecution;
import ru.yandex.qe.s3.util.Environment;
import yandex.cloud.priv.datasphere.v2.lzy.Lzy;

import java.net.URI;
import java.util.Properties;

public class S3ProxyProvider {
    private S3Proxy proxy = null;
    private static final Logger LOG = LogManager.getLogger(S3ProxyProvider.class);

    public S3ProxyProvider(){
        if (Environment.useS3Proxy())
            proxy = createProxy();
    }

    public S3ProxyProvider(Lzy.GetS3CredentialsResponse response){
        if (response.getUseS3Proxy())
            proxy = createProxy(response.getS3ProxyProvider(), response.getS3ProxyIdentity(), response.getS3ProxyCredentials());
    }

    public void stop() throws Exception {
        if (proxy != null){
            proxy.stop();
        }
    }

    public static S3Proxy createProxy(){
        return createProxy(Environment.getS3ProxyProvider(), Environment.getS3ProxyIdentity(), Environment.getS3ProxyCredentials());
    }

    public static S3Proxy createProxy(String provider, String identity, String credentials) {
        Properties properties = new Properties();
        properties.setProperty("s3proxy.endpoint", "http://127.0.0.1:8080");
        properties.setProperty("s3proxy.authorization", "aws-v2-or-v4");
        properties.setProperty("s3proxy.identity", "local-identity");
        properties.setProperty("s3proxy.credential", "local-credential");
        properties.setProperty("jclouds.provider", provider);
        properties.setProperty("jclouds.identity", identity);
        properties.setProperty("jclouds.credential", credentials);

        BlobStoreContext context = ContextBuilder
                .newBuilder(provider)
                .overrides(properties)
                .build(BlobStoreContext.class);

        S3Proxy proxy = S3Proxy.builder()
                .blobStore(context.getBlobStore())
                .endpoint(URI.create("http://127.0.0.1:8080"))
                .build();

        try {
            proxy.start();
        } catch (Exception e) {
            LOG.error(e);
            System.exit(1);
        }
        while (!proxy.getState().equals(AbstractLifeCycle.STARTED)) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                LOG.error(e);
                System.exit(1);
            }
        }
        return proxy;
    }
}
