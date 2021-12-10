package ru.yandex.cloud.ml.platform.lzy.servant.commands;

import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.commons.cli.CommandLine;
import yandex.cloud.priv.datasphere.v2.lzy.IAM;
import yandex.cloud.priv.datasphere.v2.lzy.Lzy;
import yandex.cloud.priv.datasphere.v2.lzy.LzyKharonGrpc;

import java.net.URI;
import java.util.Base64;

public class Credentials implements LzyCommand{

    @Override
    public int execute(CommandLine command) throws Exception {
        final IAM.Auth auth = IAM.Auth.parseFrom(Base64.getDecoder().decode(command.getOptionValue('a')));
        if (!auth.hasUser()) {
            throw new IllegalArgumentException("Please provide user credentials");
        }

        if (command.getArgs().length < 2)
            throw new IllegalArgumentException("Please specify credentials type");

        final URI serverAddr = URI.create(command.getOptionValue('z'));
        final ManagedChannel serverCh = ManagedChannelBuilder
                .forAddress(serverAddr.getHost(), serverAddr.getPort())
                .usePlaintext()
                .build();
        LzyKharonGrpc.LzyKharonBlockingStub kharon = LzyKharonGrpc.newBlockingStub(serverCh);

        switch (command.getArgs()[1]) {
            case "s3": {
                Lzy.GetS3CredentialsResponse resp = kharon.getS3Credentials(Lzy.GetS3CredentialsRequest.newBuilder().setAuth(auth).build());
                System.out.println(JsonFormat.printer().print(resp));
                return 0;
            }
            default: {
                System.out.println("Wrong credentials type");
                return -1;
            }
        }
    }
}
