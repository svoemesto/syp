package ru.svoemesto.syp.admin.notify

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Фильтр, клад��щий идентификатор вкладки в контекст запроса.
 *
 * Идентификатор приходит в адресе и потому проверяется: значение, не
 * прошедшее форму, не используется (см. [TabId]). Запросы без адреса подписки
 * фильтр пропускает как есть — идентификатор вкладки нужен подписке, а не
 * всему API.
 *
 * **Очистка обязательна и стоит в `finally`.** Потоки контейнера
 * переиспользуются, и оставленное значение уехало бы в следующий
 * несвязанный запрос (см. [TabIdContext]).
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class TabIdFilter : OncePerRequestFilter() {
    /**
     * Кладёт идентификатор вкладки и пропускает запрос дальше.
     *
     * @param request текущий запрос
     * @param response текущий ответ
     * @param chain остальная цепочка фильтров
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val submitted = request.getParameter(TabId.PARAMETER)
        val tabId = if (submitted == null) null else TabId.of(submitted)
        if (tabId != null) {
            TabIdContext.set(tabId)
        }
        try {
            chain.doFilter(request, response)
        } finally {
            // Снятие значения выполняется всегда — и когда запрос прошёл, и
            // когда он упал: оставленное значение живёт в переиспользуемом
            // потоке контейнера, и следующий запрос не должен его увидеть.
            TabIdContext.clear()
        }
    }
}
