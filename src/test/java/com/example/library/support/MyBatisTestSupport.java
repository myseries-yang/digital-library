package com.example.library.support;

import com.example.library.infrastructure.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import javax.sql.DataSource;

/** 为脱离 Spring 容器的文件数据库迁移测试创建相同的 MyBatis 映射。 */
public final class MyBatisTestSupport {
    private MyBatisTestSupport() {
    }

    public static SqlSessionFactory sessionFactory(DataSource dataSource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register(UtcInstantTypeHandler.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        return factory.getObject();
    }
}
