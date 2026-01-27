Стек технологий

Язык: Java 17+
Фреймворк: Spring Boot
База данных: postgresql
ORM: Hibernate (через Spring Data JPA)
Парсинг HTML: Jsoup
Сборка: Maven
Инструменты: Lombok, ForkJoinPool

 Локальный запуск 

Предварительные требования

Установленный JDK 17+
Установленная Postgresql

1. Клонирование проекта
git clone https://github.com/whiteout13/searchengine.git


2. Настройка конфигурации
В файле src/main/resources/application.yaml укажите параметры подключения к БД:
spring:

datasource: url: jdbc:postgresql://localhost:5432/search_engine_db username: ваш_пользователь password: ваш_пароль driver-class-name: org.postgresql.Driver

search-engine: sites: - url: https://pitaysya.ru/ name: "Питайся.ру" delay-min-ms: 100 delay-max-ms: 300 user-agent: Mozilla/5.0 (compatible; SearchBot/1.0) referrer: ""

    Сборка и запуск ./mvnw clean package java -jar target/*.jar

    Проверка работы

После запуска приложение будет доступно по адресу:

👉 http://localhost:8080

Вы сможете:

запустить индексацию сайтов;
выполнить поиск по леммам;
просматривать статус индексации.
