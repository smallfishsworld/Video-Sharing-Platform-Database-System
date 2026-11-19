package io.sustc;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the service layer on its own; the runner module is not needed for the tests. */
@SpringBootApplication(scanBasePackages = "io.sustc.service")
public class TestApplication {}
