package yangsirly.rag_agent.registration;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

/**
 * Spring Boot 4 与 MyBatis-Plus 的集成配置。
 *
 * <p>MyBatis-Plus 目前没有 Boot 4 专用 Starter，因此由官方 MyBatis Boot 4 Starter
 * 提供数据源和 Spring 集成，这里显式使用 MyBatis-Plus 的 SessionFactory。</p>
 */
@Configuration
public class MybatisPlusConfiguration {

	@Bean
	public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
		MybatisConfiguration configuration = new MybatisConfiguration();
		// Java 字段 passwordHash 可自动映射到数据库 password_hash。
		configuration.setMapUnderscoreToCamelCase(true);

		MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setConfiguration(configuration);
		return factoryBean.getObject();
	}
}
