package io.github.yuchoe.pagebound.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * DataSource 생성 전에 pagebound.data-dir 디렉토리를 만든다.
 * SQLite는 상위 디렉토리가 없으면 DB 파일을 열지 못한다(SQLITE_CANTOPEN).
 */
@Component
public class DataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String dataDir = environment.getProperty("pagebound.data-dir");
        if (dataDir == null) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dataDir));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create pagebound.data-dir: " + dataDir, e);
        }
    }
}
