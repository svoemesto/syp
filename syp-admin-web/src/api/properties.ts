import { request } from './http'

/** Вид владельца свойства. Ровно те же, что и в базе. */
export type PropertyOwnerKind = 'PROJECT' | 'VIDEOFILE' | 'TRACK' | 'SHOT' | 'SCENE' | 'PERSON'

/** Одно произвольное свойство. */
export interface PropertyView {
  id: number
  key: string
  value: string
  ordinal: number
}

/**
 * Читает свойства владельца.
 *
 * Владелец задаётся парой «вид и номер» — механизм один для всех, ровно как в
 * старом проекте, где висел один ключ-значение на все сущности.
 */
export async function readProperties(
  kind: PropertyOwnerKind,
  ownerId: number,
): Promise<PropertyView[]> {
  return await request<PropertyView[]>('GET', `/properties/${kind}/${ownerId}`)
}

/**
 * Записывает свойство.
 *
 * Повтор с тем же ключом заменяет значение, а не плодит вторую запись: оператор
 * вводит ключ заново при опечатке в значении.
 */
export async function writeProperty(
  kind: PropertyOwnerKind,
  ownerId: number,
  key: string,
  value: string,
): Promise<PropertyView> {
  return await request<PropertyView>('POST', `/properties/${kind}/${ownerId}`, { key, value })
}

/** Удаляет свойство по ключу. */
export async function deleteProperty(
  kind: PropertyOwnerKind,
  ownerId: number,
  key: string,
): Promise<void> {
  await request<void>('DELETE', `/properties/${kind}/${ownerId}?key=${encodeURIComponent(key)}`)
}
