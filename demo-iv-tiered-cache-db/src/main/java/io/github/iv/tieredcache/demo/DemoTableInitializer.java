package io.github.iv.tieredcache.demo;

import io.github.iv.tieredcache.demo.db.Demo;
import io.github.iv.tieredcache.demo.db.DemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
@Slf4j
@SpringBootApplication
public class DemoTableInitializer implements CommandLineRunner {

    private final DemoRepository demoRepository;

    @Override
    public void run(String... args) throws Exception {
        long count = demoRepository.count();

        List<Demo> list = new ArrayList<>();
        if (count == 0) {
            for (long i = 1; i < 1_000_000; i++) {
                var demo = new Demo();
                demo.setId(i);
                demo.setData(generateData());
                list.add(demo);

                if (list.size() > 100) {
                    demoRepository.saveAll(list);
                    list.clear();
                }
            }

            if (!list.isEmpty()) {
                demoRepository.saveAll(list);
                list.clear();
            }
        }

    }

    String generateData() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append((char) ThreadLocalRandom.current().nextInt('A', 'Z' + 1));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoTableInitializer.class, args);
    }
}
