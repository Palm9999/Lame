import slug from 'tiny-slug';

export const postPath = (title) => `/posts/${slug(title)}`;
