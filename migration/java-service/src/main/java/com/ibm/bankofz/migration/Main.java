package com.ibm.bankofz.migration;

import com.ibm.bankofz.migration.api.BankOfZApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory;

/** Starts the stub service on {@code PORT} (default 8080) under the OpenAPI base path {@code /api}. */
public final class Main {

    private Main() {
    }

    public static HttpServer start(int port) {
        URI base = URI.create("http://0.0.0.0:" + port + BankOfZApplication.BASE_PATH + "/");
        return JdkHttpServerFactory.createHttpServer(base, new BankOfZApplication());
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = start(port);
        System.out.printf("Bank-of-Z Java stub service listening on http://localhost:%d%s%n",
                server.getAddress().getPort(), BankOfZApplication.BASE_PATH);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }
}
