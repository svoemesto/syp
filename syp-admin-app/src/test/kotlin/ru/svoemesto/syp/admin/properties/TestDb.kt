package ru.svoemesto.syp.admin.properties

import ru.svoemesto.syp.core.db.Db

/**
 * Доступ к тестовой базе для проверок свойств.
 *
 * Отдельный маленький помощник: база в наборе одна, но искать её приходится
 * из разных пакетов, и тянуть оттуда лишнее не стоит.
 */
object TestDb {
    /**
     * Берёт базу из набора или отказывается.
     *
     * @return соединение
     * @throws org.opentest4j.TestAbortedException если базы нет — проверка
     *   откладывается, а не падает
     */
    fun assume(): Db =
        ru.svoemesto.syp.admin.catalog.TestDatabase
            .assumeDatabase()

    /**
     * Заводит проект и отдаёт его номер: владельцем свойства может быть проект,
     * и он создаётся тут же, потому что тестовая база строится из одних
     * миграций и данных анализа в ней нет.
     *
     * @param db соединение
     * @param имя имя проекта
     * @return номер проекта
     */
    fun createProject(
        db: Db,
        имя: String,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement("INSERT INTO tbl_projects (name, source_root) VALUES (?, ?)")
                .use { statement ->
                    statement.setString(1, имя)
                    statement.setString(2, "/srv/свойства")
                    statement.executeUpdate()
                }
            connection
                .prepareStatement("SELECT id FROM tbl_projects WHERE name = ?")
                .use { statement ->
                    statement.setString(1, имя)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "проект не записался" }
                        rows.getLong(1)
                    }
                }
        }

    /**
     * Первая живая сцена: свойствам нужен настоящий владелец.
     *
     * @param db соединение
     * @return номер сцены
     */
    fun firstScene(db: Db): Long =
        db.use { connection ->
            connection
                .prepareStatement("SELECT id FROM tbl_scenes WHERE NOT is_stale ORDER BY id LIMIT 1")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "в базе нет ни одной живой сцены" }
                        rows.getLong(1)
                    }
                }
        }

    /**
     * Сцена, отличная от указанной.
     *
     * @param db соединение
     * @param except номер сцены, которую брать нельзя
     * @return номер другой сцены
     */
    fun otherScene(
        db: Db,
        except: Long,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement("SELECT id FROM tbl_scenes WHERE NOT is_stale AND id <> ? ORDER BY id LIMIT 1")
                .use { statement ->
                    statement.setLong(1, except)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "нужна вторая живая сцена, а её нет" }
                        rows.getLong(1)
                    }
                }
        }
}
