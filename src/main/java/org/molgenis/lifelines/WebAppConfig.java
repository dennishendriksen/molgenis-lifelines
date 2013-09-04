package org.molgenis.lifelines;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import javax.xml.validation.Schema;

import nl.umcg.hl7.CatalogService;
import nl.umcg.hl7.GenericLayerCatalogService;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.molgenis.DatabaseConfig;
import org.molgenis.elasticsearch.config.EmbeddedElasticSearchConfig;
import org.molgenis.framework.db.Database;
import org.molgenis.framework.security.Login;
import org.molgenis.framework.server.MolgenisPermissionService;
import org.molgenis.framework.server.MolgenisSettings;
import org.molgenis.framework.ui.MolgenisPlugin;
import org.molgenis.lifelines.catalog.CatalogLoaderController;
import org.molgenis.lifelines.resourcemanager.GenericLayerResourceManagerService;
import org.molgenis.lifelines.studydefinition.StudyDefinitionManagerController;
import org.molgenis.lifelines.utils.GenericLayerDataBinder;
import org.molgenis.lifelines.utils.SecurityHandlerInterceptor;
import org.molgenis.omx.OmxConfig;
import org.molgenis.omx.auth.OmxPermissionService;
import org.molgenis.omx.config.DataExplorerConfig;
import org.molgenis.search.SearchSecurityConfig;
import org.molgenis.ui.CatalogueLoaderPluginPlugin;
import org.molgenis.ui.MolgenisPluginInterceptor;
import org.molgenis.ui.MolgenisUi;
import org.molgenis.ui.StudyDefinitionLoaderPluginPlugin;
import org.molgenis.ui.XmlMolgenisUi;
import org.molgenis.ui.XmlMolgenisUiLoader;
import org.molgenis.util.ApplicationContextProvider;
import org.molgenis.util.AsyncJavaMailSender;
import org.molgenis.util.FileStore;
import org.molgenis.util.GsonHttpMessageConverter;
import org.molgenis.util.SchemaLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;

@Configuration
@EnableWebMvc
@EnableAsync
@ComponentScan("org.molgenis")
@Import(
{ DatabaseConfig.class, OmxConfig.class, EmbeddedElasticSearchConfig.class, DataExplorerConfig.class,
		SearchSecurityConfig.class })
public class WebAppConfig extends WebMvcConfigurerAdapter
{
	@Autowired
	@Qualifier("unauthorizedDatabase")
	private Database unauthorizedDatabase;

	@Autowired
	private Login login;

	@Autowired
	private MolgenisSettings molgenisSettings;

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry)
	{
		registry.addResourceHandler("/css/**").addResourceLocations("/css/", "classpath:/css/");
		registry.addResourceHandler("/img/**").addResourceLocations("/img/", "classpath:/img/");
		registry.addResourceHandler("/js/**").addResourceLocations("/js/", "classpath:/js/");
		registry.addResourceHandler("/generated-doc/**").addResourceLocations("/generated-doc/");
		registry.addResourceHandler("/html/**").addResourceLocations("/html/", "classpath:/html/");
	}

	@Override
	public void configureMessageConverters(List<HttpMessageConverter<?>> converters)
	{
		converters.add(new GsonHttpMessageConverter());
		converters.add(new BufferedImageHttpMessageConverter());
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry)
	{
		String pluginInterceptPattern = MolgenisPlugin.PLUGIN_URI_PREFIX + "**";
		registry.addInterceptor(molgenisPluginInterceptor()).addPathPatterns(pluginInterceptPattern);
	}

	@Bean
	public MolgenisPluginInterceptor molgenisPluginInterceptor()
	{
		return new MolgenisPluginInterceptor(login, molgenisPermissionService(), molgenisUi());
	}

	@Bean
	public ApplicationListener<?> databasePopulator()
	{
		return new WebAppDatabasePopulator();
	}

	@Bean
	public static PropertySourcesPlaceholderConfigurer properties()
	{
		PropertySourcesPlaceholderConfigurer pspc = new PropertySourcesPlaceholderConfigurer();
		Resource[] resources = new FileSystemResource[]
		{ new FileSystemResource(System.getProperty("user.home") + "/molgenis-server.properties") };
		pspc.setLocations(resources);
		pspc.setFileEncoding("UTF-8");
		pspc.setIgnoreUnresolvablePlaceholders(true);
		pspc.setIgnoreResourceNotFound(true);
		pspc.setNullValue("@null");
		return pspc;
	}

	@Value("${mail.host:smtp.gmail.com}")
	private String mailHost;
	@Value("${mail.port:587}")
	private Integer mailPort;
	@Value("${mail.protocol:smtp}")
	private String mailProtocol;
	@Value("${mail.username}")
	private String mailUsername; // specify in molgenis-server.properties
	@Value("${mail.password}")
	private String mailPassword; // specify in molgenis-server.properties
	@Value("${mail.java.auth:true}")
	private String mailJavaAuth;
	@Value("${mail.java.starttls.enable:true}")
	private String mailJavaStartTlsEnable;
	@Value("${mail.java.quitwait:false}")
	private String mailJavaQuitWait;

	@Bean
	public JavaMailSender mailSender()
	{
		AsyncJavaMailSender mailSender = new AsyncJavaMailSender();
		mailSender.setHost(mailHost);
		mailSender.setPort(mailPort);
		mailSender.setProtocol(mailProtocol);
		mailSender.setUsername(mailUsername); // specify in
												// molgenis-server.properties
		mailSender.setPassword(mailPassword); // specify in
												// molgenis-server.properties
		Properties javaMailProperties = new Properties();
		javaMailProperties.setProperty("mail.smtp.auth", mailJavaAuth);
		javaMailProperties.setProperty("mail.smtp.starttls.enable", mailJavaStartTlsEnable);
		javaMailProperties.setProperty("mail.smtp.quitwait", mailJavaQuitWait);
		mailSender.setJavaMailProperties(javaMailProperties);
		return mailSender;
	}

	@Bean
	public FileStore fileStore()
	{
		return new FileStore(System.getProperty("user.home"));
	}

	/**
	 * Bean that allows referencing Spring managed beans from Java code which is not managed by Spring
	 * 
	 * @return
	 */
	@Bean
	public ApplicationContextProvider applicationContextProvider()
	{
		return new ApplicationContextProvider();

	}

	/**
	 * Enable spring freemarker viewresolver. All freemarker template names should end with '.ftl'
	 */
	@Bean
	public ViewResolver viewResolver()
	{
		FreeMarkerViewResolver resolver = new FreeMarkerViewResolver();
		resolver.setCache(true);
		resolver.setSuffix(".ftl");

		return resolver;
	}

	/**
	 * Configure freemarker. All freemarker templates should be on the classpath in a package called 'freemarker'
	 */
	@Bean
	public FreeMarkerConfigurer freeMarkerConfigurer()
	{
		FreeMarkerConfigurer result = new FreeMarkerConfigurer();
		result.setPreferFileSystemAccess(false);
		result.setTemplateLoaderPath("classpath:/templates/");

		return result;
	}

	@Bean
	public MultipartResolver multipartResolver()
	{
		return new StandardServletMultipartResolver();
	}

	@Bean
	public HttpClient httpClient()
	{
		DefaultHttpClient defaultHttpClient = new DefaultHttpClient(connectionManager());
		HttpParams httpParams = defaultHttpClient.getParams();
		HttpConnectionParams.setConnectionTimeout(httpParams, 2000);
		HttpConnectionParams.setSoTimeout(httpParams, 30000);
		return new DecompressingHttpClient(defaultHttpClient);
	}

	@Bean(destroyMethod = "shutdown")
	protected ClientConnectionManager connectionManager()
	{
		PoolingClientConnectionManager connectionManager = new PoolingClientConnectionManager();
		connectionManager.setDefaultMaxPerRoute(10);
		connectionManager.setMaxTotal(20);
		return connectionManager;
	}

	@Bean
	public GenericLayerDataBinder genericLayerDataBinder()
	{
		Schema eMeasureSchema = new SchemaLoader("EMeasure.xsd").getSchema();
		return new GenericLayerDataBinder(eMeasureSchema);
	}

	@Bean
	public GenericLayerCatalogService genericLayerCatalogService()
	{
		return new CatalogService().getBasicHttpBindingGenericLayerCatalogService();
	}

	@Value("${lifelines.resource.manager.service.url}")
	private String resourceManagerServiceUrl;// Specify in molgenis-server.properties

	@Bean
	public GenericLayerResourceManagerService genericLayerResourceManagerService()
	{
		return new GenericLayerResourceManagerService(httpClient(), resourceManagerServiceUrl, genericLayerDataBinder());
	}

	@Bean
	public SecurityHandlerInterceptor catalogLoaderHandlerInterceptor()
	{
		return new SecurityHandlerInterceptor(CatalogueLoaderPluginPlugin.class);
	}

	@Bean
	public MappedInterceptor catalogLoaderMappedInterceptor()
	{
		return new MappedInterceptor(new String[]
		{ CatalogLoaderController.URI + "/**" }, catalogLoaderHandlerInterceptor());
	}

	@Bean
	public MappedInterceptor studyDefinitionLoaderMappedInterceptor()
	{
		return new MappedInterceptor(new String[]
		{ StudyDefinitionManagerController.URI + "/**" }, studyDefinitionLoaderHandlerInterceptor());
	}

	@Bean
	public SecurityHandlerInterceptor studyDefinitionLoaderHandlerInterceptor()
	{
		return new SecurityHandlerInterceptor(StudyDefinitionLoaderPluginPlugin.class);
	}

	@Bean
	public MolgenisUi molgenisUi()
	{
		try
		{
			return new XmlMolgenisUi(new XmlMolgenisUiLoader(), molgenisSettings, molgenisPermissionService());
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Bean
	public MolgenisPermissionService molgenisPermissionService()
	{
		return new OmxPermissionService(unauthorizedDatabase, login);
	}
}