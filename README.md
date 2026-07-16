# TechDocs AI

Интеллектуальная база инженерной документации по объектам.

TechDocs AI хранит техническую документацию (паспорта, руководства, проектную и исполнительную документацию, спецификации, схемы, акты и др.), структурирует её по объектам и инженерным системам и позволяет задавать вопросы обычным языком, получая ответы со ссылками на конкретные документы и страницы.

## Возможности MVP

- Создание объектов и инженерных систем (АПС, СКУД, Вентиляция, ИТП и др.)
- Загрузка документов: PDF, DOCX, XLSX, XLS, TXT, CSV, JPG, PNG
- Автоматическая обработка: извлечение текста, разбиение на фрагменты, полнотекстовый поиск
- Автоматическое извлечение оборудования из Excel-спецификаций
- Реестр оборудования с подтверждением, редактированием и объединением дубликатов
- Чат с ИИ по документации с указанием источников (файл + страница)
- Количественные вопросы («Сколько дымовых извещателей?») отвечаются по структурированной базе оборудования, а не по случайным фрагментам PDF
- Поиск по одному объекту, по всем объектам или по конкретному документу

## Быстрый старт (Docker Compose)

```bash
cp .env.example .env   # при необходимости задайте AI_API_KEY
docker compose up --build
```

Приложение будет доступно на http://localhost:3000

Вход по умолчанию: `admin@techdocs.local` / `admin123`

> Без ключа ИИ-провайдера чат работает в экстрактивном режиме — показывает
> релевантные фрагменты документов с источниками. Для полноценных ответов
> задайте `AI_API_KEY` (OpenAI-совместимый API) или укажите локальный Ollama.

## Стек

```text
Backend:    Java 21 + Spring Boot 3 + Spring Security (JWT) + Liquibase
Frontend:   React 18 + TypeScript + Vite + Tailwind CSS
Database:   PostgreSQL 16 (полнотекстовый поиск tsvector + pg_trgm)
Files:      MinIO (или локальная папка: techdocs.storage.type=file)
Documents:  Apache PDFBox + Apache POI + Apache Tika
AI:         Любой OpenAI-совместимый API (OpenAI, Ollama, LM Studio)
Deployment: Docker Compose
```

## Разработка без Docker

Backend (нужны PostgreSQL и MinIO, либо `techdocs.storage.type=file`):

```bash
cd backend
mvn spring-boot:run
```

Frontend (дев-сервер с proxy на localhost:8080):

```bash
cd frontend
npm install
npm run dev
```

## Структура проекта

```text
backend/    Spring Boot приложение (пакеты: auth, object, engineeringsystem,
            document, processing, equipment, search, chat, ai, storage)
frontend/   React SPA (дизайн «Современный SaaS»: светлая тема, синий акцент)
docs/       Описание MVP
```

## Документация

- [Подробное описание MVP](docs/TechDocs_AI_MVP.md)

## Дорожная карта (после MVP)

- OCR для сканов (Tesseract/PaddleOCR)
- Синхронизация с Google Drive
- Векторный поиск (pgvector) в дополнение к полнотекстовому
- Автоматический поиск дубликатов оборудования
- Несколько пользователей и роли
- Экспорт реестра оборудования в XLSX
