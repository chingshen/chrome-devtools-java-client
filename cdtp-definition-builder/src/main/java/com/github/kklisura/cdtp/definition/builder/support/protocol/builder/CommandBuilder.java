package com.github.kklisura.cdtp.definition.builder.support.protocol.builder;

import com.github.kklisura.cdtp.definition.builder.protocol.types.Command;
import com.github.kklisura.cdtp.definition.builder.protocol.types.Domain;
import com.github.kklisura.cdtp.definition.builder.protocol.types.type.object.ObjectType;
import com.github.kklisura.cdtp.definition.builder.protocol.types.type.object.Property;
import com.github.kklisura.cdtp.definition.builder.support.java.builder.Builder;
import com.github.kklisura.cdtp.definition.builder.support.java.builder.JavaBuilderFactory;
import com.github.kklisura.cdtp.definition.builder.support.java.builder.JavaInterfaceBuilder;
import com.github.kklisura.cdtp.definition.builder.support.java.builder.support.CombinedBuilders;
import com.github.kklisura.cdtp.definition.builder.support.java.builder.support.MethodParam;
import com.github.kklisura.cdtp.definition.builder.support.protocol.builder.support.DomainTypeResolver;
import com.github.kklisura.cdtp.definition.builder.support.protocol.builder.support.PropertyHandlerResult;
import com.github.kklisura.cdtp.definition.builder.support.protocol.builder.support.TypeBuildRequest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.github.kklisura.cdtp.definition.builder.support.protocol.builder.TypesBuilder.*;
import static com.github.kklisura.cdtp.definition.builder.support.utils.StringUtils.*;

/**
 * Builds a domain commands.
 *
 * @author Kenan Klisura
 */
public class CommandBuilder {
	private String basePackageName;
	private String typesPackageName;
	private String eventPackageName;

	private JavaBuilderFactory javaBuilderFactory;

	/**
	 * Creates a new command builder.
	 *
	 * @param basePackageName  Base package name for this command.
	 * @param javaBuilderFactory Java builder factory.
	 * @param typesPackageName Package name for types.
	 * @param eventPackageName Package name for events.
	 */
	public CommandBuilder(String basePackageName, JavaBuilderFactory javaBuilderFactory, String typesPackageName,
						  String eventPackageName) {
		this.basePackageName = basePackageName;
		this.typesPackageName = typesPackageName;
		this.eventPackageName = eventPackageName;
		this.javaBuilderFactory = javaBuilderFactory;
	}

	/**
	 * Build builder.
	 *
	 * @param domain the domain
	 * @return the builder
	 */
	public Builder build(Domain domain, DomainTypeResolver domainTypeResolver) {
		JavaInterfaceBuilder interfaceBuilder = javaBuilderFactory.createInterfaceBuilder(basePackageName,
				toEnumClass(domain.getDomain()));

		if (StringUtils.isNotEmpty(domain.getDescription())) {
			interfaceBuilder.setJavaDoc(domain.getDescription());
		}

		if (Boolean.TRUE.equals(domain.getExperimental())) {
			interfaceBuilder.addAnnotation(EXPERIMENTAL_ANNOTATION);
		}

		if (Boolean.TRUE.equals(domain.getDeprecated())) {
			interfaceBuilder.addAnnotation(DEPRECATED_ANNOTATION);
		}

		List<Builder> builders = new LinkedList<>();

		List<Command> commands = domain.getCommands();
		if (CollectionUtils.isNotEmpty(commands)) {
			for (Command command : commands) {
				List<Property> parameters = command.getParameters();
				if (parameters == null) {
					parameters = new ArrayList<>();
				}

				// Generate methods with mandatory params first
				List<Property> mandatoryParams = filterParameters(parameters, property ->
						!Boolean.TRUE.equals(property.getOptional()));
				command.setParameters(mandatoryParams);
				addCommand(command, domain, interfaceBuilder, domainTypeResolver, builders);

				if (mandatoryParams.size() != parameters.size()) {
					// Generate methods with rest of the params
					command.setParameters(parameters);
					addCommand(command, domain, interfaceBuilder, domainTypeResolver, builders);
				}
			}
		}

		if (builders.isEmpty()) {
			return interfaceBuilder;
		}
		builders.add(interfaceBuilder);
		return new CombinedBuilders(builders);
	}

	private void addCommand(Command command, Domain domain, JavaInterfaceBuilder interfaceBuilder,
							DomainTypeResolver domainTypeResolver, List<Builder> builders) {
		final String method = command.getName();

		List<MethodParam> methodParams = buildMethodParams(command, domain, interfaceBuilder, domainTypeResolver,
				builders);

		String returnType = buildReturnType(command, domain, interfaceBuilder, domainTypeResolver, builders);

		interfaceBuilder.addMethod(method, command.getDescription(), methodParams, returnType);

		if (Boolean.TRUE.equals(command.getDeprecated())) {
			interfaceBuilder.addMethodAnnotation(method, DEPRECATED_ANNOTATION);
		}

		if (Boolean.TRUE.equals(command.getExperimental())) {
			interfaceBuilder.addMethodAnnotation(method, EXPERIMENTAL_ANNOTATION);
		}

		List<Property> returns = command.getReturns();
		if (CollectionUtils.isNotEmpty(returns)) {
			if (returns.size() == 1) {
				interfaceBuilder.addParametrizedMethodAnnotation(method, RETURNS_ANNOTATION, returns.get(0).getName());
			}
		}
	}

	private String buildReturnType(Command command, Domain domain, JavaInterfaceBuilder interfaceBuilder,
								   DomainTypeResolver domainTypeResolver, List<Builder> builders) {
		List<Property> returns = command.getReturns();

		TypesBuilder typesBuilder = new TypesBuilder(typesPackageName, javaBuilderFactory);

		if (CollectionUtils.isNotEmpty(returns)) {
			if (returns.size() == 1) {
				final Property property = returns.get(0);

				ObjectType objectType = new ObjectType();
				objectType.setId(toEnumClass(domain.getDomain()));
				TypeBuildRequest<ObjectType> request = new TypeBuildRequest<>(domain, objectType, domainTypeResolver);

				PropertyHandlerResult result = typesBuilder.getPropertyHandleResult(property, request,
						interfaceBuilder);

				addBuilder(domain, property, result, interfaceBuilder, builders);

				return result.getType();
			} else {
				String name = getReturnTypeFromGetter(command.getName());

				ObjectType objectType = new ObjectType();
				objectType.setId(name);
				objectType.setProperties(returns);

				TypeBuildRequest<ObjectType> request = new TypeBuildRequest<>(domain, objectType, domainTypeResolver);

				TypesBuilder.TypeHandlerResult result = typesBuilder.buildClass(request);

				builders.add(result.getBuilder());

				// Since these properties are not ref type, we need to manually import them.
				String packageName = buildPackageName(typesPackageName, domain.getDomain().toLowerCase());
				interfaceBuilder.addImport(packageName, name);

				return name;
			}
		}

		return null;
	}

	private List<MethodParam> buildMethodParams(Command command, Domain domain, JavaInterfaceBuilder interfaceBuilder,
												DomainTypeResolver domainTypeResolver, List<Builder> builders) {
		List<MethodParam> methodParams = new ArrayList<>();

		TypesBuilder typesBuilder = new TypesBuilder(typesPackageName, javaBuilderFactory);

		List<Property> parameters = command.getParameters();
		if (CollectionUtils.isNotEmpty(parameters)) {
			for (Property property : parameters) {
				ObjectType objectType = new ObjectType();
				objectType.setId(toEnumClass(domain.getDomain()));
				TypeBuildRequest<ObjectType> request = new TypeBuildRequest<>(domain, objectType, domainTypeResolver);

				PropertyHandlerResult result = typesBuilder.getPropertyHandleResult(property, request,
						interfaceBuilder);

				addBuilder(domain, property, result, interfaceBuilder, builders);

				MethodParam methodParam = new MethodParam();
				methodParam.setType(result.getType());
				methodParam.setName(property.getName());
				methodParam.setAnnotations(new ArrayList<>());

				if (Boolean.TRUE.equals(property.getDeprecated())) {
					methodParam.getAnnotations().add(new MethodParam.Annotation(DEPRECATED_ANNOTATION));
				}

				if (Boolean.TRUE.equals(property.getExperimental())) {
					methodParam.getAnnotations().add(new MethodParam.Annotation(EXPERIMENTAL_ANNOTATION));
				}

				if (Boolean.TRUE.equals(property.getOptional())) {
					methodParam.getAnnotations().add(new MethodParam.Annotation(OPTIONAL_ANNOTATION));
				}

				methodParam.getAnnotations().add(new MethodParam.Annotation(PARAM_NAME_ANNOTATION,
						methodParam.getName()));

				methodParams.add(methodParam);
			}
		}

		return methodParams;
	}

	private void addBuilder(Domain domain, Property property, PropertyHandlerResult result,
							JavaInterfaceBuilder interfaceBuilder, List<Builder> builders) {
		if (result.getBuilder() != null) {
			builders.add(result.getBuilder());

			// Since these properties are not ref type, we need to manually import them.
			String packageName = buildPackageName(typesPackageName, domain.getDomain().toLowerCase());
			String name = toEnumClass(property.getName());

			interfaceBuilder.addImport(packageName, name);
		}
	}

	private List<Property> filterParameters(List<Property> parameters, Predicate<Property> filterPredicate) {
		if (CollectionUtils.isNotEmpty(parameters)) {
			return parameters.stream()
					.filter(filterPredicate)
					.collect(Collectors.toList());
		}

		return parameters;
	}
}