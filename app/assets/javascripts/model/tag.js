module.factory('Tag', ['$resource', function ($resource) {
	return $resource('/api/tag/:id', {});
}]);
