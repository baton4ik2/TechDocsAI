# TechDocs AI

Интеллектуальная база инженерной документации по объектам.

TechDocs AI хранит техническую документацию (паспорта, руководства, проектную и исполнительную документацию, спецификации, схемы, акты и др.), структурирует её по объектам и инженерным системам и позволяет задавать вопросы обычным языком, получая ответы со ссылками на конкретные документы и страницы.

## Документация

- [Подробное описание MVP](docs/TechDocs_AI_MVP.md)

## Планируемый стек

```text
Backend:    Java 21 + Spring Boot 3
Frontend:   React + TypeScript
Database:   PostgreSQL + pgvector
Files:      MinIO
AI:         OpenAI-совместимый API
Documents:  Apache PDFBox + Apache POI + Apache Tika
Deployment: Docker Compose
```
