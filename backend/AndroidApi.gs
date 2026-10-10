/** Android compatibility: explicit methods only, with the existing server role checks. */
function mobileAndroidCall_(sessionToken, method, args) {
  if (!Array.isArray(args) || args.length > 6) validationError_('Invalid Android request.');
  const methods = {
    listRecords: listRecords,
    listRecentActivity: listRecentActivity,
    saveRecord: saveRecord,
    deleteRecord: deleteRecord,
    importRecords: importRecords,
    getCloudStatus: getCloudStatus,
    listUsers: listUsers,
    upsertUser: upsertUser,
    setUserActive: setUserActive,
    setUserRole: setUserRole,
    removeUser: removeUser,
    upsertInstaller: upsertInstaller,
    removeInstaller: removeInstaller,
    upsertLocationDirectory: upsertLocationDirectory,
    removeLocationDirectoryValue: removeLocationDirectoryValue,
    assignInstallerToLocation: assignInstallerToLocation,
    removeInstallerAssignment: removeInstallerAssignment,
    saveSourceLeadOptions: saveSourceLeadOptions,
    reverseGeocodeLocation: reverseGeocodeLocation,
    saveConsumerImage: saveConsumerImage,
    listConsumerImages: listConsumerImages,
    getConsumerImage: getConsumerImage,
    deleteConsumerImage: deleteConsumerImage,
    saveTransportationDistance: saveTransportationDistance,
    getIncentiveRates: getIncentiveRates,
    saveIncentiveRates: saveIncentiveRates,
    generateIncentiveReport: generateIncentiveReport
  };
  if (!Object.prototype.hasOwnProperty.call(methods, method)) validationError_('Unknown Android operation.');
  // Every whitelisted method revalidates the live authorized user and its role.
  return methods[method].apply(null, [sessionToken].concat(args));
}
