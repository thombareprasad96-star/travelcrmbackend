import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.schema.internal.ExceptionHandlerHaltImpl;
import org.hibernate.tool.schema.spi.ContributableMatcher;
import org.hibernate.tool.schema.spi.ExecutionOptions;
import org.hibernate.tool.schema.spi.SchemaManagementTool;
import org.hibernate.tool.schema.spi.SchemaValidator;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ValidateHibernateSchema {
    private ValidateHibernateSchema() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: ValidateHibernateSchema <classesDir> <jdbcUrl> <username> <password>");
        }

        Path classesDir = Path.of(args[0]).toAbsolutePath().normalize();
        String jdbcUrl = args[1];
        String username = args[2];
        String password = args[3];

        List<Class<?>> entities = findEntities(classesDir);
        if (entities.isEmpty()) {
            throw new IllegalStateException("No @Entity classes found under " + classesDir);
        }

        Map<String, Object> settings = new HashMap<>();
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        settings.put(AvailableSettings.HBM2DDL_AUTO, "validate");
        settings.put(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver");
        settings.put(AvailableSettings.JAKARTA_JDBC_URL, jdbcUrl);
        settings.put(AvailableSettings.JAKARTA_JDBC_USER, username);
        settings.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, password);
        settings.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY,
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        settings.put("hibernate.integration.envers.enabled", "true");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> entity : entities) {
                sources.addAnnotatedClass(entity);
            }
            Metadata metadata = sources.buildMetadata();

            SchemaManagementTool tool = registry.getService(SchemaManagementTool.class);
            SchemaValidator validator = tool.getSchemaValidator(settings);
            validator.doValidation(metadata, executionOptions(settings), ContributableMatcher.ALL);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }

        System.out.println("Hibernate schema validation passed for " + entities.size() + " entities.");
    }

    private static List<Class<?>> findEntities(Path classesDir) throws Exception {
        List<Class<?>> entities = new ArrayList<>();
        URL url = classesDir.toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new URL[]{url}, ValidateHibernateSchema.class.getClassLoader());
             var stream = Files.walk(classesDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classesDir.relativize(file).toString()
                        .replace('\\', '.')
                        .replace('/', '.')
                        .replaceAll("\\.class$", "");
                if (name.contains("$")) {
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(name, false, loader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (type.isAnnotationPresent(Entity.class) && !Modifier.isAbstract(type.getModifiers())) {
                    entities.add(type);
                }
            }
        }
        entities.sort((a, b) -> a.getName().compareTo(b.getName()));
        return entities;
    }

    private static ExecutionOptions executionOptions(Map<String, Object> settings) {
        return new ExecutionOptions() {
            @Override
            public Map<String, Object> getConfigurationValues() {
                return settings;
            }

            @Override
            public boolean shouldManageNamespaces() {
                return true;
            }

            @Override
            public org.hibernate.tool.schema.spi.ExceptionHandler getExceptionHandler() {
                return ExceptionHandlerHaltImpl.INSTANCE;
            }
        };
    }
}
