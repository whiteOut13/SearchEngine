Стек технологий

Язык: Java 17+
Фреймворк: Spring Boot
База данных: MySQL
ORM: Hibernate (через Spring Data JPA)
Парсинг HTML: Jsoup
Сборка: Maven
Инструменты: Lombok, ForkJoinPool

 Локальный запуск 

Предварительные требования

Установленный JDK 17+
Установленная MySQL 8+

1. Клонирование проекта
git clone https://github.com/whiteout13/searchengine.git

Создайте базу данных в MySQL:
CREATE DATABASE search_engine CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

3. Настройка конфигурации
В файле src/main/resources/application.yaml укажите параметры подключения к БД:
spring:

datasource: url: jdbc:mysql://localhost:3306/search_engine?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC username: ваш_пользователь password: ваш_пароль driver-class-name: com.mysql.cj.jdbc.Driver

search-engine: sites: - url: https://pitaysya.ru/ name: "Питайся.ру" delay-min-ms: 100 delay-max-ms: 300 user-agent: Mozilla/5.0 (compatible; SearchBot/1.0) referrer: ""

    Сборка и запуск ./mvnw clean package java -jar target/*.jar

    Проверка работы

После запуска приложение будет доступно по адресу:

👉 http://localhost:8080

Вы сможете:

запустить индексацию сайтов;
выполнить поиск по леммам;
просматривать статус индексации.
