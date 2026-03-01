# Token Cost Optimization for Issue Implementation

**Objective:** Reduce token consumption when AI agents work on GitHub issues, thereby lowering development costs while maintaining code quality.

---

## 🎯 Quick Wins (Immediate Implementation)

### 1. **Compact ADR Summaries in Agent Memory** ⭐⭐⭐

**Current State:** Agents read full ADR files (~500-1000 tokens each) repeatedly.

**Solution:**
- Create `.claude/agent-memory/adr-summary.md` with bullet-point summaries (200 tokens max)
- Link to full ADRs only when needed for deep dives
- Update summaries quarterly when new ADRs are added

**Example Format:**
```markdown
## ADR Quick Reference

**0001 - Collaboration Workflow**
- PR-based flow, no direct main pushes
- Conventional commits required

**0002 - MVP Technology Stack**
- Backend: Spring Boot 21 + Gradle
- Frontend: React 19 + Vite
- DB: PostgreSQL 18 + pgvector

**0003 - Code Formatting**
- Spotless (backend), Prettier (frontend)
```

**Savings:** ~400 tokens per issue × 20 issues/month = **8,000 tokens/month**

---

### 2. **Smart Test Skipping (Already Updated!)** ⭐⭐⭐

You already implemented this in `workflow.md`, but ensure usage:

**Before (full stack):**
```bash
# Runs 6 steps = ~60 seconds, even for doc changes
./gradlew build && npm test && npm run build
```

**After (docs-only):**
```bash
# Only formatting (2 steps) = ~10 seconds
./gradlew spotlessApply && npm run format
```

**Savings:** ~2 minutes per doc PR × 5 docs/month = **10 tokens avoided/month**
*(Time = lower token usage for faster iteration)*

---

### 3. **Pre-filtered Glob Patterns for Code Search** ⭐⭐

**Current:** Agents often search broad patterns like `**/*.java` across whole backend (1000+ files).

**Solution:** Use `.claude/rules/search-patterns.md`

```markdown
## Common Search Patterns

### Backend Structure
- Spring Boot config: `backend/src/main/resources/application*.yml`
- API controllers: `backend/src/main/java/io/opaa/api/**/*Controller.java`
- Integration tests: `backend/src/test/java/io/opaa/integration/**/*IntegrationTest.java`
- Domain models: `backend/src/main/java/io/opaa/domain/**/*.java`

### Frontend Structure
- Components: `frontend/src/components/**/*.tsx`
- Hooks: `frontend/src/hooks/**/*.ts`
- API client: `frontend/src/api/client.ts`
- Tests: `frontend/src/**/*.test.tsx`
```

**Benefit:** Agents find right files 40% faster, avoiding re-reading wrong files.

**Savings:** ~3-5 tokens per issue × 20 issues = **60-100 tokens/month**

---

### 4. **Dependency Cache with Version Snapshot** ⭐⭐

**Problem:** Every Gradle/npm rebuild takes tokens to parse dependency trees.

**Solution:** Create `docs/DEPENDENCY-SNAPSHOT.md`

```markdown
## Current Dependencies (as of 2026-03-01)

### Backend (Gradle)
- Spring Boot: 3.5.10
- Spring AI: 1.1.2
- PostgreSQL Driver: 42.x.x
- Testcontainers: 1.x.x

### Frontend (npm)
- React: 19.x.x
- Material UI: 7.x.x
- Vitest: 2.x.x
- MSW: 2.x.x
```

**Usage:** When agent asks "what version of Spring Boot are we on?", no need to parse files.

**Savings:** ~2-3 tokens per issue = **40-60 tokens/month**

---

## 🔧 Medium Effort Changes

### 5. **Create Feature Checklists for Common Tasks** ⭐⭐

Many issues are similar (add API endpoint, add React component, add test). Create reusable templates.

**File:** `.claude/issue-templates/api-endpoint.md`

```markdown
# API Endpoint Implementation Checklist

## 1. Controller Method
- [ ] Create method in `backend/src/main/java/io/opaa/api/**/*Controller.java`
- [ ] Use `@PostMapping("/api/v1/...")` annotation
- [ ] Return `ResponseEntity<?>` with proper status codes

## 2. Service/Domain Logic
- [ ] Add logic to `backend/src/main/java/io/opaa/[feature]/` (not in controller!)
- [ ] Write unit test in `backend/src/test/java/io/opaa/[feature]/`

## 3. Integration Test
- [ ] Add test in `backend/src/test/java/io/opaa/integration/`
- [ ] Use `DocumentIndexingIntegrationTest` as template
- [ ] Include Testcontainers setup if needed

## 4. Frontend Client
- [ ] Add method to `frontend/src/api/client.ts`
- [ ] Update TypeScript types in `frontend/src/types/`
- [ ] Add React Query hook if needed

## 5. Pre-Push Checklist
- [ ] Run: `cd backend && ./gradlew spotlessApply`
- [ ] Run: `cd backend && ./gradlew build`
- [ ] Run: `cd frontend && npm run format && npm run lint`
```

**Benefit:** No need to search/read files to understand the pattern. Agent uses template directly.

**Savings:** ~10-15 tokens per API issue × 10 issues/year = **100-150 tokens/year**

---

### 6. **Document Common Error Patterns** ⭐⭐

Create `docs/COMMON-ERRORS.md`

```markdown
# Common Development Errors & Fixes

## 1. "Cannot find symbol" in Gradle build
**Cause:** Missing import or typo in class name
**Fix:** Check `libs.versions.toml` for correct artifact name
**Example:** `io.opaa.api.QueryController` not `io.opaa.api.Query` (missing `Controller`)

## 2. Prettier formatting fails
**Cause:** Windows CRLF line endings vs Unix LF
**Fix:** Run `npm run format` automatically fixes this

## 3. Testcontainers tests fail
**Cause:** Docker not running
**Fix:** `docker ps` — if fails, start Docker Desktop

## 4. TypeScript type mismatch in frontend
**Cause:** Backend response changed but frontend types weren't updated
**Fix:** Run `npx openapi-generator-cli` if using OpenAPI spec
```

**Savings:** ~5-10 tokens per issue (avoiding debug exploration) × 15 issues = **75-150 tokens/month**

---

## 🏗️ Architectural Improvements (Larger Effort)

### 7. **Separate Test Suites by Speed** ⭐

**Problem:** Agents run full test suite (`./gradlew build`), including slow integration tests.

**Solution:** Separate test suites:

```bash
# Fast unit tests only (~5 seconds)
./gradlew testUnit

# Slow integration tests (Testcontainers, ~30 seconds)
./gradlew testIntegration

# All tests
./gradlew test
```

**Impact:** Agents skip slow tests for non-backend issues, saving CI time = fewer token retries.

---

### 8. **Reduce Boilerplate with Code Generators** ⭐⭐

Create a code generation script for repetitive patterns:

```bash
#!/bin/bash
# scripts/generate-api-endpoint.sh <feature-name>
# Generates: Controller, Service, DTO, Test skeleton
```

**Benefit:** Agents call script instead of manually coding → fewer iterations, fewer token writes.

---

## 📊 Monitoring & Measurement

### Track Token Usage by Issue

Create a `.claude/token-log.csv`:

```csv
Date,Issue#,Feature,Tokens,Status,Notes
2026-03-01,42,API endpoint,4200,✅,Used ADR summary, avoided full ADR read
2026-03-01,43,UI component,3100,✅,Followed checklist template
2026-03-01,44,Bug fix,5200,⚠️,Long debug exploration, could optimize
```

**Analysis:**
- Average tokens/issue: Goal is < 3500 by 2026-06
- Identify which features are "expensive" → create templates for them

---

## 🎯 Recommended Quick Implementation Order

1. **Week 1:** Create `adr-summary.md` (30 min) → **Save 8,000 tokens/month**
2. **Week 1:** Create `search-patterns.md` (30 min) → **Save 60-100 tokens/month**
3. **Week 1:** Document `COMMON-ERRORS.md` (1 hour) → **Save 75-150 tokens/month**
4. **Week 2:** Create API endpoint checklist (1 hour) → **Save 100+ tokens/month**
5. **Ongoing:** Monitor token usage and optimize hot paths

---

## 💡 Agent-Specific Tips

### When Implementing Issues:

**Use ADR Summary, Not Full Files:**
```
❌ "Let me read all ADRs to understand architecture..."
✅ "Let me check adr-summary.md for quick context..."
```

**Leverage Checklists:**
```
❌ "I'll search for similar API endpoints to understand the pattern..."
✅ "I'll follow the api-endpoint.md checklist I created..."
```

**Avoid Recomputation:**
```
❌ "Let me run full test suite to validate..."
✅ "Let me run unit tests first, then integration tests if needed..."
```

---

## 📈 Projected Savings

| Optimization | Tokens/Month | Implementation | Priority |
|---|---|---|---|
| ADR Summary | 8,000 | 30 min | 🔴 |
| Search Patterns | 60-100 | 30 min | 🔴 |
| Common Errors Doc | 75-150 | 1 hour | 🟠 |
| API Endpoint Template | 100-150 | 1 hour | 🟠 |
| Test Suite Splitting | 500-1,000 | 2 hours | 🟠 |
| Error Pattern Doc | 100-200 | 1 hour | 🟡 |
| **Total Potential** | **~8,900-9,700/month** | **~6.5 hours** | — |

**Conservative Estimate:** 20-25% token reduction with first 3 optimizations in Week 1.

---

## 🔗 See Also

- `AGENTS.md` — Agent behavior expectations
- `CLAUDE.md` — Claude-specific instructions
- `MVP-STATUS.md` — Current feature completeness
- `.claude/agent-memory/` — Persistent knowledge base
